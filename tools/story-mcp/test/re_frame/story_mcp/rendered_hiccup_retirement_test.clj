(ns re-frame.story-mcp.rendered-hiccup-retirement-test
  "The acceptance gate — `run-variant` produces NO rendered output, and no
  consumer may advertise one.

  ## Why this namespace exists

  There is no `:render?` run-variant option and no `:rendered-hiccup`
  result slot: rendering is `re-frame.story.render/render-variant`'s, and
  its result carries the host render under `:rendered`. An unread option
  beside a permanently-nil slot would describe a screenshot-test input
  that does not exist.

  A test that stubs `rf.story/run-variant`'s outcome map proves the
  scrubber; it cannot prove that a REAL Story run never emits the slot, so
  a stub-only suite would stay green if the slot came back. That is the
  hole this namespace closes.

  ## What is pinned

  The REAL Story-to-consumer path, end to end, with NO `with-redefs`:

  1. `rf.story/run-variant` on a really-registered variant returns a result
     map carrying no rendering slot — and passing a `:render?` option
     changes nothing about that.
  2. The `run-variant` and `preview-variant` MCP handlers, driven through
     the live `rf.story-mcp.tools.wire-pipeline/invoke-tool` boundary, project a payload
     carrying no rendering slot.
  3. No advertised tool descriptor's prose or input schema promises
     rendered output or a `:render?` knob.
  4. `render-variant` is the rendering authority and names its result
     `:rendered` — so what `run-variant` lacks is a false contract, not a
     capability.

  Companion: `run_result_roundtrip_test.clj` pins the unified result
  language across the same three surfaces."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.render :as rf.story.render]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.registry :as rf.story-mcp.tools.registry]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; ---------------------------------------------------------------------------
;; The absent names, held as data so every assertion below probes the SAME
;; set, and so a partial reintroduction (the slot but not the option, or vice
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
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.story.recorder/clear!)
  ;; story-mcp's own artefact carries no epoch dep, so this suite never loads
  ;; `re-frame.epoch`. PIN that posture rather than inherit it: `re-frame.epoch`
  ;; installs its capture hooks PROCESS-WIDE at ns-load, so any JVM that puts
  ;; epoch on this suite's classpath would switch the `:narrative` projection
  ;; to a full per-event tape and balloon the payload past the token cap.
  (rf/configure! {:epoch-history {:depth 0}})
  (rf.story/reg-story :story.cart
    {:doc "A cart." :component :app.ui/cart :tags #{:dev :test} :args {}})
  (rf.story/reg-variant :story.cart/full
    {:doc  "A cart variant with a view, so a renderer WOULD have something to render."
     :tags #{:dev :test}
     :args {:label "Checkout"}})
  (try
    (t)
    (finally
      (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- run-real!
  "Settle a REAL rf.story/run-variant on the fixture variant. Named away from
   clojure.core/run! deliberately."
  [opts]
  (deref (rf.story/run-variant :story.cart/full opts) 15000 ::timed-out))

;; ===========================================================================
;; 1 — the REAL Story boundary emits no rendering slot, with or without a
;;     `:render?` option.
;; ===========================================================================

(deftest real-run-variant-emits-no-rendering-slot
  (testing "a real rf.story/run-variant result carries no rendering slot"
    (let [outcome (run-real! nil)]
      (is (not= ::timed-out outcome) "the real run must settle")
      (is (map? outcome) "the real run returns a result map")
      (is (not (contains? outcome retired-result-slot))
          (str "run-variant MUST NOT carry " retired-result-slot
               " — rendering is render-variant's, and a permanently-nil "
               "compatibility slot would be a false contract. "
               "Result keys: " (pr-str (sort (keys outcome)))))
      (is (rf.story/valid-run-result? outcome)
          (str "the real result still conforms to the frozen run-result schema: "
               (rf.story/explain-run-result outcome)))))
  (testing "supplying a :render? option changes nothing"
    ;; The point is not merely that the option is ignored — no branch can
    ;; produce the slot from it. Feeding it a truthy value is the strongest
    ;; form of that check.
    (let [outcome (run-real! {retired-run-opt true})]
      (is (not= ::timed-out outcome) "the real run must settle")
      (is (not (contains? outcome retired-result-slot))
          (str "run-variant MUST NOT populate " retired-result-slot
               " even when handed a " retired-run-opt
               " option — run-variant has no rendered output to deliver. "
               "Result keys: " (pr-str (sort (keys outcome))))))))

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
                 "ever ship a permanently-nil compatibility field. "
                 "Payload keys: " (pr-str (sort (keys s)))))))))

;; ===========================================================================
;; 3 — no descriptor advertises rendered output.
;;     This is the arm a prose-only reintroduction would trip.
;; ===========================================================================

(deftest no-tool-descriptor-advertises-rendered-output
  (doseq [{:keys [name description inputSchema]} rf.story-mcp.tools.registry/tool-registry]
    (testing (str name " descriptor")
      (is (not (string/includes? (str description) "rendered-hiccup"))
          (str name "'s description promises `rendered-hiccup`, a slot no "
               "story-mcp payload carries — point rendering guidance at "
               "`rf.story/render-variant` and its "
               "`:rendered` result instead"))
      (is (not (contains? (:properties inputSchema) :render?))
          (str name " advertises a `:render?` input knob no tool honours")))))

;; ===========================================================================
;; 4 — rendering is a capability of render-variant, not of run-variant.
;; ===========================================================================

(deftest render-variant-remains-the-rendering-authority
  (testing "render-variant's terminal status vocabulary names :rendered"
    (is (contains? rf.story.render/statuses :rendered)
        "render-variant must be able to report a completed render"))
  (testing "the rendering authority is a distinct fn, not a run-variant option"
    (is (some? (resolve 're-frame.story.render/render-variant))
        "render-variant is the single explicit visual-rendering API")))
