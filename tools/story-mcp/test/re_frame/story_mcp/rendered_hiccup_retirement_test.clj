(ns re-frame.story-mcp.rendered-hiccup-retirement-test
  "The rf2-6r9j.13 ACCEPTANCE gate — `run-variant` produces NO rendered
  output, and no consumer may re-advertise one.

  ## Why this namespace exists

  Story once staged a `:render?` run-variant option whose result slot,
  `:rendered-hiccup`, was written unconditionally `nil`. The option was
  never read and the slot was never filled, but the public API docs, the
  normative spec and the Story-MCP tool descriptors all described it as a
  live screenshot-test input. It was retired: rendering is
  `re-frame.story.render/render-variant`'s, and its result carries the
  host render under `:rendered`.

  Every Story-MCP test that named the retired slot MANUFACTURED it in a
  stubbed `story/run-variant` outcome map (`tools_test.clj`'s
  `secret-bearing-run-result`, `dedup_test.clj`'s ratio fixture). A stub
  proves the scrubber; it cannot prove that a REAL Story run never emits
  the slot — so none of them would have reddened had the retirement been
  reverted. That is the hole this namespace closes.

  ## What is pinned

  The REAL Story-to-consumer path, end to end, with NO `with-redefs`:

  1. `story/run-variant` on a really-registered variant returns a result
     map carrying no rendering slot — and passing the retired `:render?`
     option changes nothing about that.
  2. The `run-variant` and `preview-variant` MCP handlers, driven through
     the live `wire-pipeline/invoke-tool` boundary, project a payload
     carrying no rendering slot.
  3. No advertised tool descriptor's prose or input schema promises
     rendered output or a `:render?` knob.
  4. `render-variant` remains the rendering authority and names its
     result `:rendered` — so the retirement removed a false contract
     rather than a capability.

  Companion: `run_result_roundtrip_test.clj` pins the unified result
  language across the same three surfaces."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.story :as story]
            [re-frame.story.recorder :as recorder]
            [re-frame.story.render :as render]
            [re-frame.story-mcp.config :as config]
            [re-frame.story-mcp.tools.registry :as registry]
            [re-frame.story-mcp.tools.wire-pipeline :as wire-pipeline]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; ---------------------------------------------------------------------------
;; The retired names. Kept as data so every assertion below probes the SAME
;; set, and so a partial revert (the slot back but not the option, or vice
;; versa) reddens rather than slipping through one arm.
;; ---------------------------------------------------------------------------

(def ^:private retired-result-slot :rendered-hiccup)
(def ^:private retired-run-opt     :render?)

;; ---------------------------------------------------------------------------
;; Fixture: a booted Story registry with a real variant. Mirrors
;; `run_result_roundtrip_test.clj`'s boot — the same `plain-atom` substrate a
;; consuming project installs headlessly, so the run below is the real one.
;; ---------------------------------------------------------------------------

(defn reset-story
  [t]
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (config/set-allow-writes! false)
  (config/set-allow-sensitive-reads! false)
  (schemas/clear-schemas-by-frame!)
  (recorder/clear!)
  ;; story-mcp's own artefact carries no epoch dep; under the tools-root
  ;; aggregate a Story namespace installs the capture hooks process-wide and
  ;; the `:narrative` projection then balloons the payload past the token cap.
  ;; Reproduce the artefact's epoch-free posture either way.
  (rf/configure! {:epoch-history {:depth 0}})
  (story/reg-story :story.cart
    {:doc "A cart." :component :app.ui/cart :tags #{:dev :test} :args {}})
  (story/reg-variant :story.cart/full
    {:doc  "A cart variant with a view, so a renderer WOULD have something to render."
     :tags #{:dev :test}
     :args {:label "Checkout"}})
  (try
    (t)
    (finally
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- run-real!
  "Settle a REAL story/run-variant on the fixture variant. Named away from
   clojure.core/run! deliberately."
  [opts]
  (deref (story/run-variant :story.cart/full opts) 15000 ::timed-out))

;; ===========================================================================
;; 1 — the REAL Story boundary emits no rendering slot, with or without the
;;     retired option.
;; ===========================================================================

(deftest real-run-variant-emits-no-rendering-slot
  (testing "a real story/run-variant result carries no rendering slot"
    (let [outcome (run-real! nil)]
      (is (not= ::timed-out outcome) "the real run must settle")
      (is (map? outcome) "the real run returns a result map")
      (is (not (contains? outcome retired-result-slot))
          (str "run-variant MUST NOT carry " retired-result-slot
               " — rendering is render-variant's, and a permanently-nil "
               "compatibility slot is the retired false contract (rf2-6r9j.13). "
               "Result keys: " (pr-str (sort (keys outcome)))))
      (is (story/valid-run-result? outcome)
          (str "the real result still conforms to the frozen run-result schema: "
               (story/explain-run-result outcome)))))
  (testing "supplying the RETIRED :render? option changes nothing"
    ;; The option is not merely ignored — the point is that no branch can
    ;; resurrect the slot from it. Feeding it the truthy value the old docs
    ;; advertised is the strongest form of that check.
    (let [outcome (run-real! {retired-run-opt true})]
      (is (not= ::timed-out outcome) "the real run must settle")
      (is (not (contains? outcome retired-result-slot))
          (str "run-variant MUST NOT populate " retired-result-slot
               " even when handed the retired " retired-run-opt
               " option — the old API promised exactly this and could not "
               "deliver it. Result keys: " (pr-str (sort (keys outcome))))))))

;; ===========================================================================
;; 2 — the REAL MCP handlers project no rendering slot.
;;     No `with-redefs`: this is the live wire boundary over the live run.
;; ===========================================================================

(deftest real-mcp-run-and-preview-payloads-carry-no-rendering-slot
  (doseq [tool ["run-variant" "preview-variant"]]
    (testing (str tool " over the live wire boundary")
      (let [r (invoke tool {:variant-id "story.cart/full"})
            s (:structuredContent r)]
        (is (not (true? (:isError r)))
            (str tool " must succeed against the booted plain-atom host; got "
                 (pr-str r)))
        (is (map? s) (str tool " returns a structured payload"))
        ;; Non-vacuity: the absence assertion below must be read off a REAL
        ;; run payload, never an empty or error-shaped map.
        (is (contains? s :app-db)
            (str tool " payload must be a real run projection (carries :app-db)"))
        (is (some? (:status s))
            (str tool " payload must carry the unified :status verdict"))
        (is (not (contains? s retired-result-slot))
            (str tool " MUST NOT project " retired-result-slot
                 " — Story emits no rendering slot, so projecting one could only "
                 "ever ship a permanently-nil compatibility field (rf2-6r9j.13). "
                 "Payload keys: " (pr-str (sort (keys s)))))))))

;; ===========================================================================
;; 3 — no descriptor re-advertises the retirement.
;;     This is the arm a prose-only revert would trip.
;; ===========================================================================

(deftest no-tool-descriptor-advertises-rendered-output
  (doseq [{:keys [name description inputSchema]} registry/tool-registry]
    (testing (str name " descriptor")
      (is (not (string/includes? (str description) "rendered-hiccup"))
          (str name "'s description promises `rendered-hiccup`, a slot no "
               "story-mcp payload carries — point rendering guidance at "
               "`story/render-variant` and its `:rendered` result instead "
               "(rf2-6r9j.13)"))
      (is (not (contains? (:properties inputSchema) :render?))
          (str name " advertises the retired `:render?` input knob")))))

;; ===========================================================================
;; 4 — the retirement removed a false contract, not a capability.
;; ===========================================================================

(deftest render-variant-remains-the-rendering-authority
  (testing "render-variant's terminal status vocabulary still names :rendered"
    (is (contains? render/statuses :rendered)
        "render-variant must still be able to report a completed render"))
  (testing "the rendering authority is a distinct fn, not a run-variant option"
    (is (some? (resolve 're-frame.story.render/render-variant))
        "render-variant is the single explicit visual-rendering API")))
