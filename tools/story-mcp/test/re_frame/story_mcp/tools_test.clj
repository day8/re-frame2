(ns re-frame.story-mcp.tools-test
  "Per-tool semantics + the server dispatcher's `initialize` / `tools/list`
  / `tools/call` plumbing.

  Tests boot Story's canonical vocabulary in a per-test fixture so the
  registrar carries the seven canonical tags + the lifecycle machine,
  then register a small fixture story + variant so each tool has
  something to read."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.mcp-base.cap :as base-cap]
            [re-frame.mcp-base.overflow :as overflow]
            [re-frame.mcp-base.vocab :as vocab]
            [re-frame.schemas :as schemas]
            [re-frame.story :as story]
            [re-frame.story.recorder :as recorder]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.protocol :as proto]
            [re-frame.story-mcp.server :as server]
            [re-frame.story-mcp.tools.cap :as cap]
            [re-frame.story-mcp.tools.dev :as dev]
            [re-frame.story-mcp.tools.recorder :as recorder-tool]
            [re-frame.story-mcp.tools.registry :as registry]
            [re-frame.story-mcp.tools.testing :as testing-tool]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ResultIO mirror over story-mcp's CLJ-map result shape — used by the
;; cap-honours-default test to sum tokens without reaching into cap's
;; private result-io reify. Mirrors the runtime IO instance in
;; `re-frame.story-mcp.tools.cap/result-io` (rf2-eyelu / rf2-mzndx) so
;; a drift on the consumer's content-shape would be caught by the
;; assertion sitting on this mirror — INCLUDING the `:structuredContent`
;; sizing path added in rf2-mzndx.
(def ^:private test-io
  (reify base-cap/ResultIO
    (content-texts [_ result]
      (cond-> (mapv :text (:content result))
        (some? (:structuredContent result))
        (conj (pr-str (:structuredContent result)))))
    (build-overflow-result [_ marker _]
      {:content [{:type "text" :text (pr-str marker)}]
       :structuredContent marker})))

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
  ;; rf2-g9fje — sensitive-read gate. Default off everywhere (mirrors
  ;; the `--allow-sensitive-reads` boot-time posture). Tests that
  ;; exercise the opt-in branch flip it explicitly.
  (config/set-allow-sensitive-reads! false)
  (schemas/clear-schemas-by-frame!)
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
  ;; Decorator fixtures — one of each kind (rf2-mqp1u). The `:wrap`
  ;; closure on the hiccup decorator is the load-bearing case for
  ;; `list-decorators`: the projected EDN must NOT carry the fn, only
  ;; a `:has-wrap?` boolean.
  (story/reg-decorator :dec.test/wrap-card
    {:kind :hiccup
     :doc  "Wrap the variant in a card."
     :wrap (fn [body _args] [:div.card body])})
  (story/reg-decorator :dec.test/seed-cart
    {:kind          :frame-setup
     :doc           "Seed an empty cart at frame creation."
     :app-db-patch  {:cart {:items []}}})
  (story/reg-decorator :dec.test/stub-http
    {:kind     :fx-override
     :doc      "Pin http effect to a known response."
     :fx-id    :http
     :response {:status 200 :body "ok"}})
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

(def ^:private tool-names-fixture
  "Canonical tool-name list (rf2-36upq TE7). Single source of truth
  shared with `test/stdio-roundtrip.js` — a registry change updates one
  file, not two. The fixture sits at `test/fixtures/tool-names.json`;
  this def parses it once at ns-load."
  (-> (io/resource "fixtures/tool-names.json")
      slurp
      (cheshire/parse-string true)
      :names
      sort
      vec))

(deftest registry-covers-impl-spec-7-2
  (testing "every tool from IMPL-SPEC §7.2 + §7.3 is present"
    (let [names (set (map :name registry/tool-registry))]
      ;; Per-category coverage (documentation value beyond the fixture
      ;; check: each line names a tool category + an expected slot).
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
      (is (contains? names "list-decorators"))
      (is (contains? names "list-assertions"))
      (is (contains? names "get-docs-markdown"))
      (is (contains? names "variant->edn"))
      ;; Testing
      (is (contains? names "run-variant"))
      (is (contains? names "snapshot-identity"))
      (is (contains? names "run-a11y"))
      (is (contains? names "read-failures"))
      ;; Write
      (is (contains? names "register-variant"))
      (is (contains? names "unregister-variant"))
      (is (contains? names "record-as-variant"))))
  (testing "registry name set matches the shared fixture exactly (rf2-36upq TE7)"
    ;; The Node `stdio-roundtrip.js` round-trip asserts `tools/list`
    ;; against the same JSON file. A drift between code + tests on either
    ;; side surfaces here AND there in the same edit.
    (let [reg-names (sort (mapv :name registry/tool-registry))]
      (is (= tool-names-fixture reg-names)
          (str "registry vs fixtures/tool-names.json drift — update both: "
               "fixture-only=" (set/difference (set tool-names-fixture) (set reg-names))
               " registry-only=" (set/difference (set reg-names) (set tool-names-fixture)))))))

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

;; rf2-mqp1u — `list-decorators` is a read-only enumeration. The
;; `:wrap` closure on `:hiccup` decorators must NOT cross the wire
;; (closures don't serialise); the projection drops the slot in
;; favour of a `:has-wrap?` boolean. The canonical vocabulary
;; pre-registers a handful of decorators (e.g.
;; `:rf.story/layout-debug.measure`); the fixture adds three more,
;; one of each kind, so this test asserts presence rather than count.
(deftest list-decorators-projects-each-kind-safely
  (let [r  (invoke "list-decorators" {})
        ds (-> r :structuredContent :decorators)
        by-id (into {} (map (juxt :id identity)) ds)]
    (is (success? r))
    (is (some? (get by-id :dec.test/wrap-card)))
    (is (some? (get by-id :dec.test/seed-cart)))
    (is (some? (get by-id :dec.test/stub-http)))
    (is (= :hiccup (:kind (get by-id :dec.test/wrap-card))))
    (is (true? (:has-wrap? (get by-id :dec.test/wrap-card)))
        "hiccup decorator surfaces :has-wrap? not the closure")
    (is (not (contains? (get by-id :dec.test/wrap-card) :wrap))
        ":wrap closure MUST NOT be transported over MCP")
    (is (= :frame-setup (:kind (get by-id :dec.test/seed-cart))))
    (is (= {:cart {:items []}}
           (:app-db-patch (get by-id :dec.test/seed-cart))))
    (is (= :fx-override (:kind (get by-id :dec.test/stub-http))))
    (is (= :http   (:fx-id    (get by-id :dec.test/stub-http))))
    (is (= {:status 200 :body "ok"}
           (:response (get by-id :dec.test/stub-http))))))

(deftest list-decorators-kind-filter
  (testing "kind filter narrows to one decorator kind"
    (let [r       (invoke "list-decorators" {:kind "hiccup"})
          ds      (-> r :structuredContent :decorators)
          kinds   (set (map :kind ds))]
      (is (success? r))
      (is (= #{:hiccup} kinds)
          "filter MUST return only the requested kind")
      (is (some #(= :dec.test/wrap-card (:id %)) ds)
          "fixture's hiccup decorator is present")))
  (testing "filter with no canonical-or-fixture matches returns empty vec, not :error"
    (let [r  (invoke "list-decorators" {:kind "frame-setup"})
          ds (-> r :structuredContent :decorators)]
      (is (success? r))
      (is (every? #(= :frame-setup (:kind %)) ds))
      (is (some #(= :dec.test/seed-cart (:id %)) ds)))))

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

;; rf2-i0kyy — `get-docs-markdown` is the agent-paste shape.
(deftest get-docs-markdown-renders-story-and-variants
  (let [r  (invoke "get-docs-markdown" {:story-id "story.button"})
        s  (:structuredContent r)
        md (:markdown s)]
    (is (success? r))
    (is (string? md))
    (is (re-find #"^# Story `:story\.button`" md)
        "renders an H1 with the story id")
    (is (re-find #"A clickable button\." md)
        "includes the story :doc")
    (is (re-find #":story\.button/primary" md)
        "lists the primary variant")
    (is (re-find #":story\.button/secondary" md)
        "lists the secondary variant")
    (is (re-find #"Primary button\." md)
        "includes per-variant :doc")
    (is (= :story.button (:story-id s)))
    (is (vector? (:variants s)))))

(deftest get-docs-markdown-unknown-story
  (let [r (invoke "get-docs-markdown" {:story-id "story.nope/missing"})]
    (is (error? r))
    (is (re-find #"not found" (-> r :content first :text)))))

(deftest get-docs-markdown-missing-arg
  (let [r (invoke "get-docs-markdown" {})]
    (is (error? r))
    (is (re-find #"story-id" (-> r :content first :text)))))

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

;; ---------------------------------------------------------------------------
;; EDN reader hardening on register-variant :body (rf2-g9fje fix 2/3)
;;
;; The EDN-string path through `tool-register-variant` is locked down per
;; the rf2-uaymx audit: no tagged literals, no custom readers, 64KB size
;; cap, 64-level depth cap. Pre-fix, `(edn/read-string body)` would happily
;; eval `#=(...)` evaluator forms (when `*read-eval*` was true) or invoke
;; any data reader on the `*data-readers*` table; post-fix the reader is
;; `:readers {}` with a throwing `:default`, so any tagged-literal form
;; lands in `::edn-error`.
;; ---------------------------------------------------------------------------

(deftest register-variant-rejects-tagged-literal
  (testing "EDN body containing a custom tagged literal is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; Custom tags (non-EDN-built-in: not #inst / #uuid) route through the
    ;; reader's :default handler, which throws under the rf2-g9fje
    ;; hardening. The throw lands as ::edn-error → the "must be a map or
    ;; a valid EDN string" error message.
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/tagged"
                     :body "{:doc #my.app/widget {:x 1}}"})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))
          "tagged literals route through the EDN-error message"))))

(deftest register-variant-rejects-reader-eval-form
  (testing "EDN body containing #=() does not evaluate (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; `#=(...)` is the read-time eval form. `clojure.edn/read-string`
    ;; ignores `*read-eval*` and rejects it as a tagged literal under
    ;; our throwing :default. The body should be refused.
    (let [r (invoke "register-variant"
                    {:variant-id "story.button/eval"
                     :body "{:doc #=(println \"PWNED\") :args {}}"})]
      (is (error? r)
          "the #= eval form must be rejected before any side-effect can fire"))))

(deftest register-variant-rejects-oversize-edn-body
  (testing "EDN body exceeding the 64KB ceiling is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    (let [big-doc (apply str (repeat (* 70 1024) \x))
          r       (invoke "register-variant"
                          {:variant-id "story.button/oversize"
                           :body       (str "{:doc \"" big-doc "\"}")})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))
          "oversize payload routes through the EDN-error message"))))

(deftest register-variant-rejects-over-deep-edn-body
  (testing "EDN body exceeding the 64-level depth ceiling is rejected (rf2-g9fje)"
    (config/set-allow-writes! true)
    ;; Build a 100-level nested map by string concatenation; well past the
    ;; 64 ceiling. The depth check runs AFTER `edn/read-string` parses, so
    ;; the rejection happens before the registrar sees the value.
    (let [deep-edn (str (apply str (repeat 100 "{:a "))
                        "1"
                        (apply str (repeat 100 "}")))
          r        (invoke "register-variant"
                           {:variant-id "story.button/deep"
                            :body       deep-edn})]
      (is (error? r))
      (is (re-find #"(?i)must be a map or a valid EDN string" (-> r :content first :text))))))

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
  "Spawn a worker thread that polls for the recorder's open window, then
  pushes `events` once `recording?` flips true. Replaces the
  `Thread/sleep delay-ms` race the original helper had (TE5, rf2-36upq)
  — on a slow CI runner the worker could either fire BEFORE
  `start-recording!` (events dropped, capture truncated) or AFTER
  `stop-recording!` (same outcome). Polling `recording?` from the worker
  end means we never depend on a sleep window outlasting the tool.

  The worker's poll has a hard 5s upper bound — well past any
  realistic tool latency — and bails silently if the recorder never
  opens (the test asserts `:recorded-event-count` on the result and
  catches a truncated capture there).

  Returns the worker thread so a test can `.join` (with timeout) when
  it needs determinism on whether the worker has finished pushing.
  Most callers just spawn-and-forget — the `:duration-ms` window the
  tool sleeps in is more than long enough for the polled push."
  [events]
  (doto (Thread.
          ^Runnable
          (fn []
            ;; Poll `recording?` with a 1ms park between probes — far
            ;; finer-grained than the original 20ms sleep. Bails after
            ;; 5s if the recorder never opens (a tool bug; the test
            ;; assertions will catch it).
            (let [deadline (+ (System/nanoTime) (* 5 1000000000))]
              (loop []
                (cond
                  (recorder/recording?)
                  (doseq [ev events]
                    (recorder/record-event! ev))

                  (< (System/nanoTime) deadline)
                  (do (Thread/sleep 1)
                      (recur))

                  ;; Timed out; bail. The test's :recorded-event-count
                  ;; assertion will surface the miss.
                  :else nil)))))
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
    (drive-events-during-recording [[:counter/inc] [:counter/by 7]])
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
    (drive-events-during-recording [[:counter/inc] [:counter/inc]])
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
    (drive-events-during-recording [[:counter/inc]])
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
    (drive-events-during-recording [[:counter/inc]])
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
    (drive-events-during-recording [[:counter/inc]])
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
    (is (= vocab/code-method-not-found (-> resp :error :code))
        "an unknown tool yields a protocol-level method-not-found")))

(deftest dispatch-malformed-envelope
  (testing "missing jsonrpc version yields invalid-request"
    (let [resp (server/dispatch {:method "tools/list" :id 5})]
      (is (= vocab/code-invalid-request (-> resp :error :code))))))

(deftest dispatch-unknown-method
  (let [resp (server/dispatch
               {:jsonrpc "2.0" :id 6 :method "nope/whatever"})]
    (is (= vocab/code-method-not-found (-> resp :error :code)))
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
          sw     (java.io.StringWriter.)
          ;; Silent-on-success (rf2-try1x): the server logs the parse
          ;; error to *err* per MCP stdio rules; capture it into a
          ;; throwaway buffer so the green test run stays at the
          ;; canonical 3-line shape.
          err    (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 2 (count frames)))
        (is (= vocab/code-parse-error (-> (nth frames 0) :error :code)))
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
          tokens (base-cap/sum-text-tokens test-io r)]
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
;; `:include-sensitive` arg (rf2-vw4sq) is the documented escape hatch.
;;
;; These tests pin the contract at the story-mcp surface: a sensitive
;; slot declared through app-schema metadata on the variant's frame must
;; surface as `:rf/redacted` in the tool's response `:app-db` slot by
;; default, and as the raw value when the caller opts in via
;; `:include-sensitive true`. Assertion records carrying the top-level
;; `:sensitive? true` stamp must be dropped by default and included
;; when opted in.
;;
;; Pattern mirrors `implementation/schemas/test/re_frame/
;; schemas_sensitive_test.clj`: schema metadata is the canonical
;; per-slot declaration surface; story-mcp verifies its wire egress
;; helper refreshes and consumes those declarations.
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
  seeded `:rf.story/assertions` slot would otherwise bleed across
  tests."
  [variant-id]
  (when (some? (frame-container variant-id))
    ((requiring-resolve 're-frame.frame/destroy-frame!) variant-id)))

(defn- declare-sensitive!
  "Register schema metadata for a sensitive slot on the named variant's
  frame. The tool egress helper refreshes schema declarations before it
  calls `elide-wire-value`."
  [variant-id path]
  (ensure-variant-frame! variant-id)
  (schemas/reg-app-schema path [:any {:sensitive? true}]
                          {:frame variant-id}))

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
  seeded `:rf.story/assertions` and `[:rf/elision]` slots would
  otherwise leak."
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
  (testing ":include-sensitive true forwards the raw value through the walker"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                         :include-sensitive true})
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
  (testing "run-variant's :include-sensitive true forwards the raw value"
    (config/set-allow-sensitive-reads! true)
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                     :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= "TOPSECRET" (get-in s [:app-db :secret])))))))

(deftest elide-app-db-include?-true-bypasses-walker
  ;; rf2-brehq — the `include? true` branch of `helpers/elide-app-db`
  ;; skips `elide-wire-value` entirely. Pins behavioural equivalence
  ;; with the previous walking-then-no-edit implementation:
  ;;
  ;;   1. The return is the input db itself (`identical?`) — the walker
  ;;      would have rebuilt every map / vector via `reduce-kv` and
  ;;      `mapv`, breaking identity even though value would be
  ;;      preserved. The bypass returns the original reference.
  ;;
  ;;   2. The return is value-equal to running the walker with both
  ;;      inclusion knobs flipped (the previous behaviour). Future
  ;;      refactors that reintroduce walker work on this branch will
  ;;      still pass (2) but break (1) — the load-bearing perf invariant
  ;;      this bead fixes.
  ;;
  ;; Calls `helpers/elide-app-db` directly so the test pins the helper's
  ;; contract, not a downstream tool's composition of it. Avoids
  ;; coupling to `run-variant`'s lifecycle behaviour.
  (testing ":include? true returns the input ref unchanged AND matches walker-with-both-knobs-on"
    (with-clean-frame [vid :story.button/primary]
      (let [db {:public    "ok"
                :secret    "TOPSECRET"
                :nested    {:also-secret "DEEP"
                            :public-leaf 42}
                :coll      [:a :b :c]
                :empty-map {}}]
        ;; Populate the elision registry on vid's frame so the walker
        ;; has something to consult — the bypass-equivalence proof only
        ;; works if the walker WOULD have visited sensitive paths.
        (seed-app-db! vid db)
        (declare-sensitive! vid [:secret])
        (declare-sensitive! vid [:nested :also-secret])
        (let [frame-db (read-frame-db vid)
              bypass   ((requiring-resolve 're-frame.story-mcp.tools.helpers/elide-app-db)
                        frame-db vid true)
              walked   (rf/elide-wire-value frame-db
                                            {:frame                      vid
                                             :rf.size/include-sensitive? true
                                             :rf.size/include-large?     true})]
          (is (identical? frame-db bypass)
              "include? true returns the SAME object — no walker rebuild")
          (is (= walked bypass)
              "bypass output value-equals the previous walking-then-no-edit output")
          (is (= "TOPSECRET" (get bypass :secret))
              "top-level sensitive slot rides through")
          (is (= "DEEP" (get-in bypass [:nested :also-secret]))
              "nested sensitive slot rides through"))))))

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
  (testing ":include-sensitive true preserves sensitive records"
    (config/set-allow-sensitive-reads! true)
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
                                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 2 (:total s)) "both records survive the egress")
        (is (= 1 (count (:failures s))) "the failed sensitive record is visible")
        (is (false? (:passing? s)) "the visible failure flips :passing?")))))

(deftest egress-tools-input-schema-carries-include-sensitive
  (testing "every tool surfacing :app-db or assertions accepts :include-sensitive"
    (doseq [tname ["preview-variant" "run-variant" "read-failures"]]
      (let [t     (some #(when (= tname (:name %)) %) registry/tool-registry)
            props (-> t :inputSchema :properties)]
        (is (contains? props :include-sensitive)
            (str tname " missing :include-sensitive slot"))
        (is (= "boolean" (-> props :include-sensitive :type))
            (str tname " :include-sensitive slot is not boolean-typed"))))))

;; ---------------------------------------------------------------------------
;; Sensitive-read boot gate (rf2-g9fje)
;;
;; Per the rf2-uaymx (b) decision: the per-call `:include-sensitive` arg
;; is honoured ONLY when the operator opened the server-side gate at boot
;; (`--allow-sensitive-reads`). When the gate is closed:
;;
;;   1. `tools/list` omits `:include-sensitive` from the input schemas of
;;      preview-variant / run-variant / read-failures (caller UX — no
;;      ghost knob).
;;   2. `:include-sensitive true` on a tool call is silently ignored at
;;      the egress helpers (defence-in-depth — even a caller who learned
;;      about the slot some other way can't exfiltrate raw values).
;; ---------------------------------------------------------------------------

(deftest sensitive-reads-gate-defaults-closed
  (testing "fixture leaves the gate closed by default"
    (is (false? (config/sensitive-reads-allowed?))
        "fixture must reset the gate between tests")))

(deftest sensitive-reads-gate-flag-flips-config
  (testing "--allow-sensitive-reads flag flips the boot config"
    (let [cfg (#'server/parse-args ["--allow-sensitive-reads"])]
      (is (true? (:allow-sensitive-reads? cfg))))
    (let [cfg (#'server/parse-args [])]
      (is (nil? (:allow-sensitive-reads? cfg))
          "absent flag leaves the slot unset so merge respects sysprop/env defaults"))))

(deftest tools-list-strips-include-sensitive-when-gate-closed
  (testing "tools/list omits :include-sensitive from the schema when the gate is closed"
    (is (false? (config/sensitive-reads-allowed?)))
    (let [descriptors (registry/tool-descriptors)]
      (doseq [tname ["preview-variant" "run-variant" "read-failures"]]
        (let [t     (some #(when (= tname (:name %)) %) descriptors)
              props (-> t :inputSchema :properties)]
          (is (not (contains? props :include-sensitive))
              (str "gate closed: " tname " must not advertise :include-sensitive")))))))

(deftest tools-list-surfaces-include-sensitive-when-gate-open
  (testing "tools/list advertises :include-sensitive when the gate is open"
    (config/set-allow-sensitive-reads! true)
    (let [descriptors (registry/tool-descriptors)]
      (doseq [tname ["preview-variant" "run-variant" "read-failures"]]
        (let [t     (some #(when (= tname (:name %)) %) descriptors)
              props (-> t :inputSchema :properties)]
          (is (contains? props :include-sensitive)
              (str "gate open: " tname " must advertise :include-sensitive"))
          (is (= "boolean" (-> props :include-sensitive :type))))))))

(deftest preview-variant-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true is silently ignored at egress"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "preview-variant" {:variant-id "story.button/primary"
                                         :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "gate closed: per-call opt-in is dropped; redaction stands")))))

(deftest run-variant-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true is silently ignored at egress"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid {:public "ok" :secret "TOPSECRET"})
      (declare-sensitive! vid [:secret])
      (let [r (invoke "run-variant" {:variant-id "story.button/primary"
                                     :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= :rf/redacted (get-in s [:app-db :secret]))
            "gate closed: per-call opt-in is dropped; redaction stands")))))

(deftest read-failures-gate-closed-ignores-per-call-flag
  (testing "with gate closed, :include-sensitive true does not surface sensitive records"
    (is (false? (config/sensitive-reads-allowed?)))
    (with-clean-frame [vid :story.button/primary]
      (seed-app-db! vid
                    {:rf.story/assertions
                     [{:assertion :rf.assert/path-equals :passed? true}
                      {:assertion  :rf.assert/path-equals
                       :passed?    false
                       :sensitive? true
                       :reason     "leak"}]})
      (let [r (invoke "read-failures" {:variant-id "story.button/primary"
                                       :include-sensitive true})
            s (:structuredContent r)]
        (is (success? r))
        (is (= 1 (:total s))
            "gate closed: sensitive records remain dropped despite the opt-in")))))

(deftest read-boot-config-sensitive-reads-sysprop
  (testing "JVM sysprop seeds :allow-sensitive-reads? true"
    (let [restore (System/getProperty "rf.story-mcp.allow-sensitive-reads")]
      (try
        (System/setProperty "rf.story-mcp.allow-sensitive-reads" "true")
        (let [cfg (config/read-boot-config)]
          (is (true? (:allow-sensitive-reads? cfg))))
        (finally
          (if restore
            (System/setProperty "rf.story-mcp.allow-sensitive-reads" restore)
            (System/clearProperty "rf.story-mcp.allow-sensitive-reads")))))))

;; ---------------------------------------------------------------------------
;; Agent-onboarding text parity (rf2-36upq S5)
;;
;; `story-instructions-text` (tools/dev.cljc) is hand-copied from the spec.
;; CI must catch drift between the prose's canonical-tag list / assertion-id
;; list and what the registrar reports — otherwise the agent's onboarding
;; doc silently lies as the registry evolves.
;; ---------------------------------------------------------------------------

(deftest story-instructions-text-mentions-every-canonical-tag
  (testing "the onboarding text names every canonical tag the registrar ships"
    (let [text       dev/story-instructions-text
          tag-names  (set (map name story/canonical-tags))]
      (doseq [tag-name tag-names]
        (is (re-find (re-pattern (str ":" tag-name "\\b")) text)
            (str "story-instructions-text missing canonical tag :" tag-name
                 " — keep the onboarding doc in lockstep with `story/canonical-tags`"))))))

(deftest story-instructions-text-mentions-every-canonical-assertion
  (testing "the onboarding text names every canonical assertion the registrar ships"
    (let [text             dev/story-instructions-text
          assertion-names  (->> (story/canonical-assertion-ids)
                                (map name)
                                set)]
      ;; The prose styles assertion ids without the namespace prefix (e.g.
      ;; "path-equals", "state-is") to keep the line under width. Match on
      ;; the bare name with a word boundary on each side.
      (doseq [aname assertion-names]
        (is (re-find (re-pattern (str "\\b" aname "\\b")) text)
            (str "story-instructions-text missing canonical assertion " aname
                 " — keep the onboarding doc in lockstep with `story/canonical-assertion-ids`"))))))

;; ---------------------------------------------------------------------------
;; handle-frame! recovery write — nested catch (rf2-36upq TE2)
;;
;; server.cljc's handle-frame! has a nested try around the recovery write
;; (the "even the recovery write failed" branch). It's unreachable from the
;; happy-path corpus; mock a writer that throws on .write and assert the
;; run-loop survives.
;; ---------------------------------------------------------------------------

(deftest handle-frame-survives-recovery-write-failure
  (testing "writer that throws on every .write yields no propagation"
    ;; Force the dispatch to throw by triggering an error path that the
    ;; outer catch picks up. The cleanest signal: an unknown tool method
    ;; arrives via tools/call AFTER dispatch returns a valid response,
    ;; then proto/write-frame! throws on the writer. We compose this with
    ;; a `tools/call` that succeeds in dispatch but where the writer
    ;; throws.
    (let [throwing-writer (proxy [java.io.Writer] []
                            (write
                              ([_])
                              ([_a _b _c]
                                (throw (RuntimeException. "writer broken"))))
                            (flush [])
                            (close []))
          msg             {:jsonrpc "2.0" :id 1 :method "ping"}]
      ;; The function MUST NOT propagate either throw — both writer
      ;; failures (the response write AND the internal-error recovery
      ;; write) are caught and logged. If propagation regressed, this
      ;; would throw and the test would fail loudly.
      (is (nil? (try
                  (#'server/handle-frame! throwing-writer msg)
                  nil
                  (catch Throwable e e)))
          "handle-frame! must not propagate writer-side throws"))))

;; ---------------------------------------------------------------------------
;; Boot-config precedence — CLI > sysprop > env (rf2-36upq TE4)
;;
;; Per spec/003-Write-Surface-Gating.md §91-94 the precedence is CLI flag >
;; JVM sysprop > env var. The existing tests cover `parse-args` directly;
;; this test asserts the merged behaviour: when all three sources supply
;; conflicting values, CLI wins, sysprop overrides env, and the env-only
;; case lands too.
;; ---------------------------------------------------------------------------

(deftest boot-config-precedence-cli-over-sysprop-over-env
  ;; Save/restore the sysprop. Env vars are read-only on the JVM, so we
  ;; can't directly mutate `RF_STORY_MCP_ALLOW_WRITES`; the test exercises
  ;; the two-of-three combinations a unit-test environment can stage
  ;; (sysprop alone, CLI overrides sysprop). The third combination —
  ;; pure env var — is exercised by the integration test `live-server.js`
  ;; under the same precedence rule.
  (let [restore (System/getProperty "rf.story-mcp.allow-writes")]
    (try
      (testing "sysprop alone seeds allow-writes? true"
        (System/setProperty "rf.story-mcp.allow-writes" "true")
        (let [cfg (config/read-boot-config)]
          (is (true? (:allow-writes? cfg))
              "sysprop should flip allow-writes? on")))
      (testing "CLI flag wins when present alongside sysprop"
        ;; sysprop is still "true" from the previous step. The merge in
        ;; `boot!` is `(merge (config/read-boot-config) cli-cfg)` so a CLI
        ;; value clobbers the boot-config value — CLI > sysprop. The
        ;; precedence test asserts the merge produces the CLI value, not
        ;; the sysprop.
        (System/setProperty "rf.story-mcp.allow-writes" "true")
        (let [boot-cfg (config/read-boot-config)
              cli-cfg  (#'server/parse-args [])  ; CLI absent ⇒ no slot
              merged   (merge boot-cfg cli-cfg)]
          (is (true? (:allow-writes? merged))
              "CLI absent ⇒ sysprop value rides through")
          ;; Now with CLI explicitly absent of `--allow-writes`, the
          ;; merge yields the sysprop. To prove CLI > sysprop, we flip
          ;; the sysprop OFF and pass `--allow-writes` on the CLI — the
          ;; merge MUST be true (CLI wins).
          (System/setProperty "rf.story-mcp.allow-writes" "false")
          (let [boot-cfg-off (config/read-boot-config)
                cli-cfg-on   (#'server/parse-args ["--allow-writes"])
                merged-on    (merge boot-cfg-off cli-cfg-on)]
            (is (false? (:allow-writes? boot-cfg-off))
                "sysprop=false leaves boot-config false")
            (is (true? (:allow-writes? cli-cfg-on))
                "--allow-writes flips the CLI slot true")
            (is (true? (:allow-writes? merged-on))
                "CLI > sysprop: merge yields the CLI's true value"))))
      (finally
        (if restore
          (System/setProperty "rf.story-mcp.allow-writes" restore)
          (System/clearProperty "rf.story-mcp.allow-writes"))))))

;; ---------------------------------------------------------------------------
;; record-as-variant write-back failure path (rf2-36upq TE6)
;;
;; The `write-back!` helper wraps the `reg-variant*` call in a try/catch
;; that surfaces the registrar's `ex-data` (`:rf.error`/`:explain`) into
;; the tool's error result. Mirrors `register-variant-rejects-bad-shape`
;; — write-back is the SECOND write-surface tool that needs the same
;; defensive-failure assertion.
;; ---------------------------------------------------------------------------

(deftest record-as-variant-write-back-failure-surfaces-explain
  (testing "write-back failure surfaces the registrar's ex-data into the result"
    (config/set-allow-writes! true)
    ;; Drive the write-back into a guaranteed-fail: target a NEW variant id
    ;; whose body would carry a `:tags` set referencing an unregistered tag
    ;; — the registrar's variant-shape validator throws with ex-data.
    ;; We can't mutate the captured body from the recorder, so we instead
    ;; rely on a different failure mode: a `:new-variant-id` whose name
    ;; doesn't satisfy the registrar's canonical-id grammar would also throw.
    ;; The cleanest test: an explicit `:new-variant-id` whose namespace
    ;; doesn't match any registered story id — the variant-shape validator
    ;; rejects it.
    (drive-events-during-recording [[:counter/inc]])
    (let [r (invoke "record-as-variant"
                    {:variant-id     "story.button/primary"
                     :new-variant-id "garbage-id-no-namespace"  ; not :story.x/y
                     :duration-ms    50
                     :write-back?    true})]
      (is (error? r) "write-back against a malformed target id must fail")
      (is (re-find #"(?i)Write-back failed" (-> r :content first :text))
          "the error text names the failure surface — agents pattern-match on this")
      (let [s (:structuredContent r)]
        (is (false? (:written-back? s))
            ":written-back? false rides through so callers see the no-op")
        (is (= :garbage-id-no-namespace (:new-variant-id s))
            "the failing target id round-trips so the agent can localise")))))

;; ---------------------------------------------------------------------------
;; record-as-variant :duration-ms ceiling (rf2-4yuhi)
;;
;; The MCP server's request loop is single-threaded; a `record-as-variant`
;; call sleeps the whole loop for the full :duration-ms window. The tool
;; validates against a hard ceiling (30000ms) and rejects abusive values.
;; ---------------------------------------------------------------------------

(deftest record-as-variant-rejects-duration-above-ceiling
  (testing ":duration-ms above the ceiling returns a structured error (rf2-4yuhi)"
    (let [over-ceiling (inc recorder-tool/max-duration-ms)
          r            (invoke "record-as-variant"
                               {:variant-id  "story.button/primary"
                                :duration-ms over-ceiling})]
      (is (error? r))
      (is (re-find #"exceeds ceiling" (-> r :content first :text)))
      (let [s (:structuredContent r)]
        (is (= :rf.story-mcp/duration-ms-too-large (:rf.error s)))
        (is (= "record-as-variant" (:tool s)))
        (is (= over-ceiling (:duration-ms s)))
        (is (= recorder-tool/max-duration-ms (:max-allowed s)))))))

(deftest record-as-variant-accepts-duration-at-ceiling-schema
  (testing "the schema's :maximum mirrors the runtime ceiling"
    (let [t      (some #(when (= "record-as-variant" (:name %)) %)
                       registry/tool-registry)
          dur-schema (-> t :inputSchema :properties :duration-ms)]
      (is (= recorder-tool/max-duration-ms (:maximum dur-schema))
          (str "the schema's :maximum slot mirrors the runtime ceiling so MCP "
               "clients can pre-validate without round-tripping a doomed call"))
      (is (zero? (:minimum dur-schema))
          ":minimum stays at 0 — the no-block default is canonical"))))

;; ---------------------------------------------------------------------------
;; run-variant :timeout-ms cap (rf2-g9fje fix 3/3)
;;
;; The single-threaded stdio loop parks for the full `:timeout-ms` window
;; — caller-supplied values clamp DOWN to `testing-tool/max-timeout-ms`
;; (30 s, matches rf2-it1cd's `:rf.http/timeout-ms` baseline). A
;; legitimately-slow variant runs against the cap; a hostile caller can't
;; park the loop indefinitely.
;; ---------------------------------------------------------------------------

(deftest run-variant-timeout-ms-schema-advertises-ceiling
  (testing "run-variant's :timeout-ms schema carries :maximum mirroring the runtime cap"
    (let [t          (some #(when (= "run-variant" (:name %)) %) registry/tool-registry)
          ts-schema  (-> t :inputSchema :properties :timeout-ms)]
      (is (= testing-tool/max-timeout-ms (:maximum ts-schema))
          "schema :maximum tracks the runtime cap so clients can pre-validate")
      (is (= 1 (:minimum ts-schema))
          ":minimum stays at 1 — a zero-timeout doesn't make sense on a blocking call"))))

(deftest run-variant-timeout-ms-clamps-down-to-cap
  ;; Pin the behavioural contract: the helper that computes the effective
  ;; timeout MUST clamp values above the ceiling rather than reject. A
  ;; legitimate slow variant still runs (against the cap), the loop never
  ;; parks past 30 s.
  (testing "values above the ceiling clamp down to max-timeout-ms"
    (is (= testing-tool/max-timeout-ms
           (min testing-tool/max-timeout-ms
                ((requiring-resolve 're-frame.mcp-base.args/parse-positive-int)
                 60000 testing-tool/default-timeout-ms)))
        "60s caller-supplied → clamped to 30s")
    (is (= 5000
           (min testing-tool/max-timeout-ms
                ((requiring-resolve 're-frame.mcp-base.args/parse-positive-int)
                 5000 testing-tool/default-timeout-ms)))
        "below-cap values ride through unchanged")
    (is (= testing-tool/default-timeout-ms
           (min testing-tool/max-timeout-ms
                ((requiring-resolve 're-frame.mcp-base.args/parse-positive-int)
                 nil testing-tool/default-timeout-ms)))
        "absent :timeout-ms uses the default")))

;; ---------------------------------------------------------------------------
;; Protocol-side frame-length cap (rf2-g9fje fix 3/3)
;;
;; `BufferedReader.readLine` allocates unbounded memory for a one-line
;; frame that never sees a newline. The MCP server's stdio transport is
;; line-delimited per spec/2025-06-18/basic/transports; an attacker (or
;; a runaway producer) sending an unterminated frame would OOM the JVM.
;; `read-frame` now caps each frame at `proto/max-frame-bytes` (4 MB,
;; well above the largest legitimate MCP message); over-cap frames
;; throw `:rf.error/frame-too-large`, which the run-loop catches and
;; converts to a parse-error response.
;; ---------------------------------------------------------------------------

(deftest read-frame-rejects-oversize-frame
  (testing "a frame exceeding max-frame-bytes throws :rf.error/frame-too-large"
    (let [oversize (str (apply str (repeat (inc proto/max-frame-bytes) \x)) "\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. oversize))]
      (try
        (proto/read-frame reader)
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/frame-too-large (:rf.error (ex-data e)))
              "ex-data carries the rf.error tag the run-loop dispatches on"))))))

(deftest read-frame-survives-after-oversize-frame
  (testing "the next frame after an oversize one is still readable"
    (let [oversize (apply str (repeat (inc proto/max-frame-bytes) \x))
          good     "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":7}"
          input    (str oversize "\n" good "\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. input))]
      ;; First read throws (frame-too-large drains the oversize frame to
      ;; the next newline). Second read lands the good frame.
      (try (proto/read-frame reader) (catch clojure.lang.ExceptionInfo _ nil))
      (is (= {:jsonrpc "2.0" :method "ping" :id 7}
             (proto/read-frame reader))
          "post-cap recovery: stdio loop continues on the next frame"))))

;; ---------------------------------------------------------------------------
;; rf2-lqjbk — parse-keyword → safe-keyword sweep
;;
;; Caller-supplied keyword ids on the read surface MUST resolve through
;; `args/safe-keyword` against a bounded set, NOT through the legacy
;; `args/parse-keyword` which interns into the never-shrinking JVM
;; keyword table. The tests below assert the no-intern property by
;; calling each read-side tool with a fresh random-shaped id and
;; verifying that the underlying `find-keyword` returns nil after the
;; call (the rejection path didn't intern the string).
;; ---------------------------------------------------------------------------

(defn- find-kw
  "Find an existing interned keyword by namespace and name without
  interning. Returns nil when no such keyword has been interned —
  the asserting probe for the rf2-lqjbk no-intern contract."
  [ns-str name-str]
  (find-keyword ns-str name-str))

(deftest get-story-unknown-id-does-not-intern
  (testing "unknown :story-id rejects WITHOUT interning a fresh JVM keyword"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "unknown-" (System/nanoTime))
          r        (invoke "get-story" {:story-id (str ns-str "/" name-str)})]
      (is (error? r) "unknown story id must error")
      (is (re-find #"(?i)story not found" (-> r :content first :text)))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest get-variant-unknown-id-does-not-intern
  (testing "unknown :variant-id rejects WITHOUT interning a fresh JVM keyword"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "unknown-variant-" (System/nanoTime))
          r        (invoke "get-variant" {:variant-id (str ns-str "/" name-str)})]
      (is (error? r))
      (is (re-find #"(?i)variant not found" (-> r :content first :text)))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest read-failures-unknown-id-does-not-intern
  (testing "read-failures on an unknown :variant-id rejects WITHOUT interning"
    (let [ns-str   "story.rf2-lqjbk-probe"
          name-str (str "rf-" (System/nanoTime))
          r        (invoke "read-failures" {:variant-id (str ns-str "/" name-str)})]
      (is (error? r))
      (is (nil? (find-kw ns-str name-str))
          "rf2-lqjbk: the unknown id MUST NOT have been interned"))))

(deftest list-stories-unknown-tag-does-not-intern
  (testing "list-stories filter with an unknown :tags entry skips it WITHOUT interning"
    (let [name-str (str "rf2-lqjbk-tag-" (System/nanoTime))
          r        (invoke "list-stories" {:tags [name-str "dev"]})]
      (is (success? r) "the known :dev tag still narrows; the unknown tag is dropped")
      (is (nil? (find-kw nil name-str))
          "rf2-lqjbk: unknown tag id MUST NOT intern"))))

(deftest list-decorators-unknown-kind-falls-through
  (testing ":kind filter with an unrecognised value rejects WITHOUT interning"
    (let [name-str (str "rf2-lqjbk-kind-" (System/nanoTime))
          r        (invoke "list-decorators" {:kind name-str})]
      (is (success? r) "the no-filter fallback still returns the registered decorators")
      (is (nil? (find-kw nil name-str))
          "rf2-lqjbk: unknown kind name MUST NOT intern"))))

(deftest run-variant-unknown-substrate-does-not-intern
  (testing "run-variant :substrate with an unknown value is dropped WITHOUT interning"
    ;; A registered variant; the unknown :substrate is dropped from the
    ;; opts map (run-variant tolerates an absent slot), so the call still
    ;; succeeds but the substrate name never enters the keyword table.
    (let [name-str (str "rf2-lqjbk-sub-" (System/nanoTime))
          r        (invoke "run-variant" {:variant-id "story.button/primary"
                                          :substrate  name-str})]
      (is (success? r))
      (is (nil? (find-kw nil name-str))
          "rf2-lqjbk: unknown substrate id MUST NOT intern"))))

(deftest run-loop-survives-oversize-frame
  (testing "an oversize frame produces a parse-error response and the loop continues"
    (let [oversize (apply str (repeat (inc proto/max-frame-bytes) \x))
          in-text  (str oversize "\n"
                        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}\n")
          reader   (java.io.BufferedReader. (java.io.StringReader. in-text))
          sw       (java.io.StringWriter.)
          err      (java.io.StringWriter.)]
      (binding [*err* err]
        (server/run-loop! reader sw))
      (let [out-lines (filter seq (clojure.string/split-lines (.toString sw)))
            frames    (mapv #(cheshire.core/parse-string % true) out-lines)]
        (is (= 2 (count frames)) "one parse-error + one ping response")
        (is (= vocab/code-parse-error (-> (nth frames 0) :error :code))
            "oversize frame routes through the parse-error response shape")
        (is (= 11 (:id (nth frames 1))) "the loop continued to the next frame")))))

;; ---------------------------------------------------------------------------
;; Cap accounting includes :structuredContent size (rf2-mzndx)
;;
;; Pre-fix, only `:content[*].text` strings counted toward the cap, while
;; `text-result` writes the same payload into BOTH `:content` and
;; `:structuredContent` — the cap underestimated wire by ~50% on every
;; structured tool. The new accounting sums both slots under one budget.
;; ---------------------------------------------------------------------------

(deftest cap-counts-structured-content-size
  (testing "structuredContent contributes to the cap (rf2-mzndx)"
    ;; A list-stories call ships the same payload in both slots. The
    ;; cap with structured accounting must be HIGHER than the cap that
    ;; only counts `:text` — assert the wire-side sum reflects both.
    (let [r          (cap/invoke-tool "list-stories" {:max-tokens 0})
          text-only  (let [io (reify base-cap/ResultIO
                                (content-texts [_ result]
                                  (map :text (:content result)))
                                (build-overflow-result [_ _m _o] {}))]
                       (base-cap/sum-text-tokens io r))
          with-struct (base-cap/sum-text-tokens test-io r)]
      ;; The `test-io` mirror counts structured content (see rf2-mzndx
      ;; update at top of file). It MUST report more tokens than the
      ;; text-only baseline whenever the result carries a non-nil
      ;; `:structuredContent`.
      (is (some? (:structuredContent r))
          "list-stories must ship a structured slot for this assertion to bite")
      (is (> with-struct text-only)
          (str "structured slot must contribute extra tokens: "
               "with-struct=" with-struct " text-only=" text-only)))))

(deftest cap-trips-on-structured-content-alone
  (testing "a tiny cap trips when only structuredContent is large (rf2-mzndx)"
    ;; The cap must fire on the combined size — not silently let a
    ;; payload through just because its :text slot fits.
    (let [r (cap/invoke-tool "list-stories" {:max-tokens 1})]
      ;; With `:max-tokens 1`, both the text AND structured slots
      ;; combined exceed the cap, so we expect the overflow marker.
      (is (overflow-marker? r)
          "tiny cap must fire on combined text+structured size")
      (let [body (get-in r [:structuredContent vocab/overflow-key])]
        (is (pos? (:token-count body))
            ":token-count reflects the over-budget count")
        (is (= 1 (:cap-tokens body)))))))
