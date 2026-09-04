(ns re-frame.story-mcp.run-result-roundtrip-test
  "The rf2-3nbl5.6 ACCEPTANCE gate — the unified run-result is a FROZEN,
  schema-backed public contract: ONE result language spoken IDENTICALLY
  across the CLJS test surface, the Story UI Test mode, and story-mcp.

  This is the cross-surface test the freeze ruling mandates: a result
  object moves **CLJS test → Story UI → MCP with NO semantic translation**.
  It lives in the story-mcp test corpus because that is the only artefact
  on the require graph that can see ALL THREE surfaces at once:

  - the CLJS run boundary — `re-frame.story.result/run-result` (the same
    pure assembly `rf.story/run` / `rf.story/is` drive);
  - the Story UI Test mode consumer — `re-frame.story.ui.state.tests`
    (`aggregate-summary` + `record-test-run`, the `.cljc` the sidebar dot
    and Test pane read);
  - the MCP serialisation path — `run-variant` / `read-failures` through
    the live `rf.story-mcp.tools.wire-pipeline/invoke-tool` boundary.

  The contract: the verdict is `:status` (`result-status` / `result-passed?`);
  there is NO `:passing?` boolean and NO lifecycle-as-verdict (the clean
  break, rf2-ba86n.17). Every surface reads `:status` off the SAME object
  with NO translation shim, and every surface's object conforms to the ONE
  Malli schema (`rf.story/run-result-schema`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.result :as rf.story.result]
            [re-frame.story.ui.state.tests :as rf.story.ui.state.tests]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---- fixture: a booted Story registry with a real failing variant -------

(defn reset-story
  [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.story.recorder/clear!)
  ;; Disable epoch-ring recording for the duration of each story-mcp test
  ;; (restored below). story-mcp's OWN artefact carries NO epoch dep, so
  ;; `cd tools/story-mcp && clojure -M:test` never loads `re-frame.epoch`
  ;; and `run-variant`'s `:narrative` projection reads an empty tape. This
  ;; call PINS that posture rather than inheriting it: `re-frame.epoch`
  ;; installs its capture hooks PROCESS-WIDE at ns-load, so any JVM that
  ;; puts epoch on this suite's classpath would silently switch
  ;; `run-variant` to a full per-event narrative (each beat carrying full
  ;; :db / trace-events), ballooning the wire payload past the MCP token cap
  ;; — the whole run-result replaced by a `:rf.mcp/overflow` marker, failing
  ;; the shape assertions. `(rf/configure! {:epoch-history {:depth 0}})` is a
  ;; core-facade knob that no-ops when epoch is absent and disables ring
  ;; recording when present, so it is correct under either classpath.
  (rf/configure! {:epoch-history {:depth 0}})
  (rf.story/reg-story :story.cart
    {:doc "A cart." :component :app.ui/cart :tags #{:dev :test} :args {}})
  ;; A variant carrying a REAL failing assertion (a path-equals against a
  ;; value the run won't produce), so the round-trip exercises a :fail
  ;; verdict end-to-end, not only the vacuous-green path.
  (rf.story/reg-variant :story.cart/red
    {:doc "A failing cart variant."
     :tags #{:dev :test}
     :assertions [[:rf.assert/path-equals [:cart/total] 99]]})
  (try
    (t)
    (finally
      ;; Restore the shipped epoch-ring default so any namespace running after
      ;; this one in the same JVM sees the normal depth-50 posture.
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

;; ===========================================================================
;; STEP 0 — the CLJS run boundary produces a result that IS the contract
;; ===========================================================================

(deftest cljs-result-conforms-to-the-frozen-schema
  (testing "the pure CLJS run boundary emits a schema-valid run-result"
    ;; This is the SAME assembly `rf.story/run` / `rf.story/is` drive — pure
    ;; data → data, runnable under `clojure -M:test` with no host.
    (let [green (rf.story.result/run-result {:epoch-tape []})
          red   (rf.story.result/run-result
                  {:assertions [{:assertion :rf.assert/path-equals
                                 :payload [[:cart/total] 99]
                                 :passed? false :expected 99 :actual nil}]})]
      (is (rf.story/valid-run-result? green)
          (str "green result must conform; "
               (rf.story/explain-run-result green)))
      (is (rf.story/valid-run-result? red)
          (str "red result must conform; "
               (rf.story/explain-run-result red)))
      (is (= :pass (rf.story/result-status green)))
      (is (= :fail (rf.story/result-status red)))
      (is (true?  (rf.story/result-passed? green)))
      (is (false? (rf.story/result-passed? red)))
      (is (not (contains? green :passing?)) "NO :passing? boolean — the clean break")
      (is (not (contains? red   :passing?))))))

;; ===========================================================================
;; THE ROUND-TRIP — ONE object, three surfaces, NO translation
;; ===========================================================================

(deftest result-moves-cljs-to-ui-to-mcp-with-no-translation
  (testing "a CLJS run-result is consumed UNCHANGED by Story UI Test mode
            AND serialised UNCHANGED through the story-mcp run-variant path —
            same keys, same :status, no shim anywhere"
    ;; --- Surface 1: the CLJS run-result (the pure boundary). -------------
    (let [cljs-result
          (rf.story.result/run-result
            {:variant/id :story.cart/red
             :assertions [{:assertion :rf.assert/path-equals
                           :payload [[:cart/total] 99]
                           :passed? false :expected 99 :actual nil}]})]

      (is (rf.story/valid-run-result? cljs-result))
      (is (= :fail (rf.story/result-status cljs-result)))

      ;; --- Surface 2: Story UI Test mode reads :status off the SAME ------
      ;; object. No translation: aggregate-summary buckets by each record's
      ;; unified :status, and record-test-run writes the run-level :status
      ;; straight through to the sidebar dot.
      (let [summary (assoc (rf.story.ui.state.tests/aggregate-summary (:assertions cljs-result))
                           :status (:status cljs-result))
            ui-state (rf.story.ui.state.tests/record-test-run {} :story.cart/red summary)
            ui-status (rf.story.ui.state.tests/variant-test-status ui-state :story.cart/red)]
        (is (= 1 (:failed summary)) "the UI fold sees the failing record")
        (is (false? (:all-passed? summary)))
        (is (= :fail ui-status)
            "Story UI Test mode reports the SAME verdict, read off the same :status"))

      ;; --- Surface 3: story-mcp serialises the SAME shape over the wire. --
      ;; A live run-variant produces the unified result through the very
      ;; same `rf.story.result/run-result` assembly; the MCP handler PROJECTS its
      ;; slots (no re-derived verdict). We assert the wire payload speaks
      ;; the identical language: a :status enum, NO :passing?, and the
      ;; structuredContent conforms to the same frozen schema.
      (let [wire   (invoke "run-variant" {:variant-id "story.cart/red"})
            s      (:structuredContent wire)]
        (is (vector? (:content wire)))
        (is (= :story.cart/red (:frame s)))
        (is (= :fail (:status s))
            "the MCP wire verdict is the SAME :status the CLJS boundary minted")
        (is (= (rf.story/result-status cljs-result) (:status s))
            "CLJS :status == MCP :status — one language, no translation")
        (is (not (contains? s :passing?))
            "the retired :passing? boolean never appears on the wire")
        (is (rf.story/valid-run-result? s)
            (str "the MCP wire payload conforms to the SAME frozen schema; "
                 (rf.story/explain-run-result s)))))))

;; ===========================================================================
;; ACCESSOR AGREEMENT — result-passed? agrees with result-status everywhere
;; ===========================================================================

(deftest result-passed-agrees-with-result-status
  (testing "result-passed? is true IFF result-status is :pass — for every verdict"
    (doseq [[status result] [[:pass       {:status :pass       :assertions [] :checks [] :consumed-selectors #{}}]
                             [:fail       {:status :fail       :assertions [] :checks [] :consumed-selectors #{}}]
                             [:cannot-run {:status :cannot-run :assertions [] :checks [] :consumed-selectors #{}}]
                             [:error      {:status :error      :assertions [] :checks [] :consumed-selectors #{}}]]]
      (is (rf.story/valid-run-result? result) (str status " result must conform"))
      (is (= status (rf.story/result-status result)))
      (is (= (= :pass status) (rf.story/result-passed? result))
          (str "result-passed? must agree with :status for " status)))))

;; ===========================================================================
;; read-failures rides the SAME contract (the re-read path)
;; ===========================================================================

(deftest read-failures-speaks-the-same-status-language
  (testing "a run then read-failures both report :status — no :passing? boolean"
    (rf.story-mcp.config/set-allow-writes! true)
    (invoke "run-variant" {:variant-id "story.cart/red"})
    (let [rf (invoke "read-failures" {:variant-id "story.cart/red"})
          s  (:structuredContent rf)]
      (is (contains? s :status) "read-failures carries the unified verdict")
      (is (not (contains? s :passing?)) "NO :passing? on read-failures either")
      ;; the re-read aggregates the accumulator (no tape floor), but the
      ;; failing path-equals record drives :fail through the same rule.
      (is (= :fail (:status s))
          "the failing record drives :fail via the same aggregation rule"))))
