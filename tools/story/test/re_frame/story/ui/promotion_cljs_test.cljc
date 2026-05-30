(ns re-frame.story.ui.promotion-cljs-test
  "Tests for the generated-failure promotion UX (rf2-ba86n.13, spec/021 §3).

  Two tiers (mirrors `save_variant_cljs_test`):

  - **JVM + CLJS** (pure machinery in `promotion.cljc`) — the artifact-id /
    label derivation, the `result->artifact` capture-source rule, the
    draft → promote-opts projection, and the `(reg-variant …)` snippet
    shape. These pin the contract the dialog + sidebar + test-pane depend
    on, AND assert promotion drives the EXISTING `re-frame.story.promotion`
    substrate (non-destructive, distinct from save-current-state).

  - **CLJS-only** (the capture store + dialog ratoms + render depend on
    Reagent / DOM) — capture is idempotent + non-destructive across
    promote, the dialog hiccup renders the curation controls + snippet, and
    the promote button drives the substrate register path.

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` build (ns suffix `-cljs-test`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.artifact  :as artifact]
            [re-frame.story.promotion :as promotion]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.promotion :as ui-promo]))

;; ---- fixtures -----------------------------------------------------------
;;
;; Reset the Story side-table around each test so the `promote!` /
;; substrate register assertions start from a clean registrar. Mirrors the
;; substrate `promotion_test` fixture shape — a one-arg fn that runs on both
;; targets.

(defn reset-state! [t]
  (registrar/clear-all!)
  ;; The substrate's `reg-variant*` validates tags against the registered
  ;; set; `clear-all!` wipes the canonical tags the real Story boot installs
  ;; (`re-frame.story/install-canonical-vocabulary!`). Re-install them so a
  ;; promoted `#{:test}` regression validates exactly as it does at runtime.
  (registrar/install-canonical-tags!)
  #?(:cljs (reset! ui-promo/captured-atom {}))
  #?(:cljs (reset! ui-promo/dialog-atom ui-promo/initial-dialog-state))
  (t))

(use-fixtures :each reset-state!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A two-step run artifact with a stubbed result + seed."
  []
  (artifact/make-run-artifact
    {:event-program [[:dispatch [:counter/init 5]]
                     [:dispatch [:counter/inc]]]
     :seed          42
     :result        {:status :fail}}))

;; ===========================================================================
;; PURE: artifact id + label
;; ===========================================================================

(deftest artifact-id-is-stable-and-seed-keyed
  (testing "the same artifact yields the same id (idempotent capture key)"
    (let [art (sample-artifact)]
      (is (= (ui-promo/artifact-id art) (ui-promo/artifact-id art)))
      (is (qualified-keyword? (ui-promo/artifact-id art)))
      (is (= "rf.test.artifact" (namespace (ui-promo/artifact-id art))))
      (is (str/includes? (name (ui-promo/artifact-id art)) "42")
          "a seeded artifact keys on its seed"))))

(deftest artifact-id-hash-keyed-without-seed
  (testing "a seedless artifact still gets a stable content-hash id"
    (let [art (artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})]
      (is (qualified-keyword? (ui-promo/artifact-id art)))
      (is (= (ui-promo/artifact-id art) (ui-promo/artifact-id art))))))

(deftest artifact-label-reads-status-and-steps
  (testing "the label scans status + step-count without opening the artifact"
    (let [label (ui-promo/artifact-label (sample-artifact))]
      (is (str/includes? label "fail"))
      (is (str/includes? label "2 steps")))))

;; ===========================================================================
;; PURE: result->artifact (the capture source rule)
;; ===========================================================================

(deftest result->artifact-prefers-replay-backlink
  (testing "a result carrying a :run-artifact back-link uses it verbatim"
    (let [art    (sample-artifact)
          result {:status :fail :run-artifact art}]
      (is (= art (ui-promo/result->artifact result []))
          "the replay back-link IS the captured artifact"))))

(deftest result->artifact-synthesizes-from-play-events
  (testing "a plain run synthesizes an artifact from its flat play-events"
    (let [result      {:status :pass}
          play-events [[:counter/inc] [:counter/dec]]
          art         (ui-promo/result->artifact result play-events)]
      (is (artifact/run-artifact? art))
      (is (= 2 (count (artifact/program-events art)))
          "both bare events lift into the dispatch program"))))

(deftest result->artifact-nil-when-nothing-replayable
  (testing "no back-link AND no play-events → nothing to capture"
    (is (nil? (ui-promo/result->artifact {:status :pass} [])))
    (is (nil? (ui-promo/result->artifact nil [])))))

;; ===========================================================================
;; PURE: draft → promote-opts + snippet
;; ===========================================================================

(deftest draft->promote-opts-projects-only-set-slots
  (testing "the draft projects to the substrate opts keeping only set slots"
    (let [opts (ui-promo/draft->promote-opts
                 {:variant-id  :story.x/regression-1
                  :doc         "  why this matters  "
                  :tags        #{:test :agent}
                  :setup-count 1
                  :extends     :story.x/source})]
      (is (= :story.x/regression-1 (:variant/id opts)))
      (is (= "why this matters" (:doc opts)) "doc is trimmed")
      (is (= #{:test :agent} (:tags opts)))
      (is (= 1 (:setup-count opts)))
      (is (= :story.x/source (:extends opts)))))
  (testing "blank / empty / negative slots are dropped, id preserved as nil"
    (let [opts (ui-promo/draft->promote-opts
                 {:variant-id nil :doc "   " :tags #{} :setup-count -1})]
      (is (contains? opts :variant/id))
      (is (nil? (:variant/id opts)))
      (is (not (contains? opts :doc)))
      (is (not (contains? opts :tags)))
      (is (not (contains? opts :setup-count))))))

(deftest promotion-snippet-emits-reg-variant-with-provenance
  (testing "the snippet is a (reg-variant …) form carrying the source link"
    (let [art  (sample-artifact)
          snip (ui-promo/promotion-snippet art {:variant-id :story.x/regression-1
                                                :tags #{:test}})]
      (is (str/starts-with? snip "(story/reg-variant "))
      (is (str/includes? snip ":story.x/regression-1"))
      (is (str/includes? snip ":run-artifact")
          "promotion always carries the source-artifact provenance link")
      (is (str/includes? snip ":script")
          "the captured program lands on :script by default")
      (is (str/includes? snip ":tags"))
      (is (str/ends-with? snip "})")))))

(deftest promotion-snippet-honours-setup-cut
  (testing "setup-count moves leading steps from :script to :setup"
    (let [art  (sample-artifact)
          snip (ui-promo/promotion-snippet art {:variant-id :story.x/r
                                                :setup-count 1})]
      (is (str/includes? snip ":setup")
          "the first step becomes a precondition")
      (is (str/includes? snip ":script")
          "the remaining step is the behaviour under test"))))

;; ===========================================================================
;; The promote path drives the EXISTING substrate (non-destructive, distinct)
;; ===========================================================================

(deftest snippet-mirrors-substrate-body
  (testing "the previewed snippet is built from the SAME body the substrate
            registers — `artifact->variant-body` (no reimplemented logic)"
    (let [art  (sample-artifact)
          opts {:variant/id :story.x/r :tags #{:test}}
          body (promotion/artifact->variant-body art opts)]
      ;; the snippet renders the substrate body's slots verbatim
      (is (str/includes? (ui-promo/promotion-snippet art {:variant-id :story.x/r
                                                          :tags #{:test}})
                         (pr-str (:run-artifact body)))
          "the snippet's :run-artifact is the substrate's trimmed link"))))

;; ===========================================================================
;; CLJS-only: the capture store
;; ===========================================================================

#?(:cljs
   (deftest capture-is-idempotent
     (testing "capturing the same run twice yields one store entry"
       (reset! ui-promo/captured-atom {})
       (let [art (sample-artifact)
             id1 (ui-promo/capture! art :story.x/v)
             id2 (ui-promo/capture! art :story.x/v)]
         (is (= id1 id2))
         (is (= 1 (count (ui-promo/captured-entries))))))))

#?(:cljs
   (deftest capture-skips-non-artifacts
     (testing "a non-artifact value is not captured"
       (reset! ui-promo/captured-atom {})
       (is (nil? (ui-promo/capture! {:not :an-artifact})))
       (is (empty? (ui-promo/captured-entries))))))

#?(:cljs
   (deftest capture-from-result-uses-source-rule
     (testing "capture-from-result! captures the synthesized artifact"
       (reset! ui-promo/captured-atom {})
       (let [id (ui-promo/capture-from-result! {:status :fail}
                                               [[:counter/inc]]
                                               :story.x/v)]
         (is (some? id))
         (is (= 1 (count (ui-promo/captured-entries))))))))

;; ===========================================================================
;; CLJS-only: promote! is non-destructive + drives the substrate register
;; ===========================================================================

#?(:cljs
   (deftest promote-registers-and-leaves-artifact-as-evidence
     (testing "promote! registers a variant AND keeps the source artifact"
       (reset! ui-promo/captured-atom {})
       (let [art (sample-artifact)
             id  (ui-promo/capture! art :story.x/v)
             pid (ui-promo/promote! id {:variant-id :story.x/regression-1
                                        :tags #{:test}})]
         (is (= :story.x/regression-1 pid)
             "the registered variant id is returned")
         (is (registrar/registered? :variant :story.x/regression-1)
             "the variant is registered through the substrate")
         (is (contains? @ui-promo/captured-atom id)
             "NON-DESTRUCTIVE — the source artifact stays as evidence")
         (let [body (registrar/handler-meta :variant :story.x/regression-1)]
           (is (contains? body :run-artifact)
               "the registered body carries the source-artifact provenance"))))))

#?(:cljs
   (deftest promote-no-ops-without-id
     (testing "promote! with no :variant-id registers nothing (the substrate
               would otherwise throw :rf.error/story-promote-no-id)"
       (reset! ui-promo/captured-atom {})
       (let [id (ui-promo/capture! (sample-artifact) :story.x/v)]
         (is (nil? (ui-promo/promote! id {:variant-id nil})))))))

#?(:cljs
   (deftest forget-does-not-unregister-promoted-variant
     (testing "forgetting the capture leaves an already-promoted variant intact"
       (reset! ui-promo/captured-atom {})
       (let [art (sample-artifact)
             id  (ui-promo/capture! art :story.x/v)]
         (ui-promo/promote! id {:variant-id :story.x/regression-2 :tags #{:test}})
         (ui-promo/forget! id)
         (is (not (contains? @ui-promo/captured-atom id))
             "the capture-store entry is gone")
         (is (registrar/registered? :variant :story.x/regression-2)
             "the promoted variant survives — promotion copied a link, not a ref")))))

;; ===========================================================================
;; CLJS-only: the dialog hiccup
;; ===========================================================================

#?(:cljs
   (deftest dialog-nil-when-closed
     (testing "the dialog renders nil when no artifact is open"
       (reset! ui-promo/dialog-atom ui-promo/initial-dialog-state)
       (let [comp (ui-promo/promotion-dialog)]
         ;; form-2 component: the returned inner render fn yields nil closed
         (is (nil? (comp)))))))

#?(:cljs
   (deftest dialog-renders-curation-controls-and-snippet-when-open
     (testing "open dialog renders the id input, doc, tags, setup-cut + snippet"
       (reset! ui-promo/captured-atom {})
       (let [art (sample-artifact)
             id  (ui-promo/capture! art :story.counter/happy)]
         (ui-promo/open! id)
         (let [comp (ui-promo/promotion-dialog)
               flat (str (comp))]
           (is (str/includes? flat "story-promotion-dialog")
               "the shared review-dialog renders under the promotion prefix")
           (is (str/includes? flat "story-promotion-snippet")
               "the (reg-variant …) snippet preview renders")
           (is (str/includes? flat "story-promotion-curation")
               "the promotion-specific curation block renders")
           (is (str/includes? flat "story-promotion-doc-input")
               "the doc input renders")
           (is (str/includes? flat "story-promotion-setup-count-input")
               "the precondition/behaviour cut input renders")
           (is (str/includes? flat "story-promotion-tag-chip")
               "the tag chips render")
           (is (str/includes? flat "story-promotion-primary")
               "the primary 'promote' action renders")
           (is (str/includes? flat "DISTINCT from save-current-state")
               "the hint states promotion is distinct from save-current-state"))))))

#?(:cljs
   (deftest dialog-open-seeds-test-tag-and-setup-zero
     (testing "open! seeds a runnable-test default draft"
       (reset! ui-promo/captured-atom {})
       (let [id (ui-promo/capture! (sample-artifact) :story.counter/happy)]
         (ui-promo/open! id)
         (let [draft (:draft @ui-promo/dialog-atom)]
           (is (contains? (:tags draft) :test) "seeds :test so it's runnable")
           (is (= 0 (:setup-count draft)) "conservative cut — whole program is behaviour")
           (is (= :story.counter/happy (:extends draft))
               "extends the origin variant for component/decorator inheritance"))))))
