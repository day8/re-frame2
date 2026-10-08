(ns re-frame.story.ui.promotion-cljs-test
  "Tests for the generated-failure promotion UX (spec/021 §3).

  The pure machinery in `promotion.cljc` — label, `result->artifact`, the
  draft → promote-opts projection and the `(reg-variant …)` snippet — runs on
  the JVM and on the CLJS node-test build. The capture store, `promote!` and
  the dialog render are CLJS-only."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [#?(:clj clojure.edn :cljs cljs.reader) :as edn]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.artifact  :as rf.story.artifact]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.promotion :as rf.story.promotion]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]))

;; ---- fixtures -----------------------------------------------------------

(defn reset-state! [t]
  (rf.story.registrar/clear-all!)
  ;; The substrate's `reg-variant*` validates tags against the registered
  ;; set; `clear-all!` wipes the canonical tags the real Story boot installs.
  ;; Re-install them so a promoted `#{:test}` regression validates as it
  ;; does at runtime.
  (rf.story.registrar/install-canonical-tags!)
  #?(:cljs (reset! rf.story.ui.promotion/captured-atom {}))
  #?(:cljs (reset! rf.story.ui.promotion/dialog-atom rf.story.ui.promotion/initial-dialog-state))
  (t))

(use-fixtures :each reset-state!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A two-step run artifact with a stubbed result + seed."
  []
  (rf.story.artifact/make-run-artifact
    {:event-program [[:dispatch [:counter/init 5]]
                     [:dispatch [:counter/inc]]]
     :seed          42
     :result        {:status :fail}}))

(defn- pasted-body
  "Read the snippet back the way an author's paste does: the body map of the
  `(reg-variant id body)` form."
  [snippet]
  (nth (edn/read-string snippet) 2))

;; ===========================================================================
;; PURE: artifact label
;; ===========================================================================

(deftest artifact-id-is-stable-and-seed-keyed
  (testing "the same artifact yields the same id (idempotent capture key)"
    (let [art (sample-artifact)]
      (is (= (rf.story.ui.promotion/artifact-id art) (rf.story.ui.promotion/artifact-id art)))
      (is (qualified-keyword? (rf.story.ui.promotion/artifact-id art)))
      (is (= "rf.test.artifact" (namespace (rf.story.ui.promotion/artifact-id art))))
      (is (str/includes? (name (rf.story.ui.promotion/artifact-id art)) "42")
          "a seeded artifact keys on its seed"))))

(deftest artifact-id-hash-keyed-without-seed
  (testing "a seedless artifact still gets a stable content-hash id"
    (let [art (rf.story.artifact/make-run-artifact {:event-program [[:dispatch [:e]]]})]
      (is (qualified-keyword? (rf.story.ui.promotion/artifact-id art)))
      (is (= (rf.story.ui.promotion/artifact-id art) (rf.story.ui.promotion/artifact-id art))))))

(deftest artifact-label-reads-status-and-steps
  (let [label (rf.story.ui.promotion/artifact-label (sample-artifact))]
    (is (str/includes? label "fail"))
    (is (str/includes? label "2 steps"))))

;; ===========================================================================
;; PURE: result->artifact (the capture source rule)
;; ===========================================================================

(deftest result->artifact-prefers-replay-backlink
  (let [art (sample-artifact)]
    (is (= art (rf.story.ui.promotion/result->artifact {:status :fail :run-artifact art} [])))))

(deftest result->artifact-synthesizes-from-play-events
  (let [art (rf.story.ui.promotion/result->artifact {:status :pass}
                                                    [[:counter/inc] [:counter/dec]])]
    (is (rf.story.artifact/run-artifact? art))
    (is (= [[:dispatch [:counter/inc]] [:dispatch [:counter/dec]]] (:event-program art))
        "both bare events lift into the dispatch program")))

(deftest result->artifact-nil-when-nothing-replayable
  ;; no back-link, no play-events, and no registered source → nothing to capture
  (is (nil? (rf.story.ui.promotion/result->artifact {:status :pass} [])))
  (is (nil? (rf.story.ui.promotion/result->artifact
              {:status :fail :variant/id :story.x/never-registered} []))))

(deftest result->artifact-captures-the-source-program-when-nothing-was-dispatched
  ;; A run whose script dispatches nothing — a :setup precondition and
  ;; [:assert …] checkpoints only, the login_form testbed's shape — captures
  ;; the source variant's stepped program. :setup is NOT folded in: the
  ;; dialog's default draft :extends the source, which already supplies it.
  (rf.story.registrar/reg-variant* :story.x/checkpoints
    {:setup  [[:x/boot]]
     :script [[:assert [:rf.assert/path-equals [:n] 1]]]})
  (let [result {:status :fail :variant/id :story.x/checkpoints}
        art    (rf.story.ui.promotion/result->artifact result [])]
    (is (= [[:assert [:rf.assert/path-equals [:n] 1]]] (:event-program art)))
    (is (= result (:result art)) "the result rides along, naming the source"))
  ;; a source with no :script still captures, as an empty program
  (rf.story.registrar/reg-variant* :story.x/declared
    {:setup      [[:x/boot]]
     :assertions [[:rf.assert/path-equals [:n] 1]]})
  (let [art (rf.story.ui.promotion/result->artifact
              {:status :fail :variant/id :story.x/declared} [])]
    (is (rf.story.artifact/run-artifact? art))
    (is (= [] (:event-program art)))))

(deftest result->artifact-compiles-the-source-program-with-the-run-inputs
  ;; :substrate is not a compile input and is not recorded
  (rf.story.registrar/reg-mode* :Mode.x/expect-two {:args {:expected 2}})
  (rf.story.registrar/reg-variant* :story.x/mode-input
    {:setup  [[:x/boot]]
     :script [[:assert [:rf.assert/path-equals [:n] [:arg :expected]]]]})
  (let [result   {:status :fail :variant/id :story.x/mode-input}
        run-opts {:active-modes   [:Mode.x/expect-two]
                  :cell-overrides nil
                  :substrate      :reagent}
        art      (rf.story.ui.promotion/result->artifact result [] run-opts)]
    (is (nil? (rf.story.ui.promotion/result->artifact result []))
        "without the run's inputs the source does not compile")
    (is (= [[:assert [:rf.assert/path-equals [:n] 2]]] (:event-program art))
        "the checkpoint holds the value the mode supplied")
    (is (= {:active-modes [:Mode.x/expect-two]} (get-in art [:source :run-opts]))
        "the artifact records the compile inputs, and only those")))

;; ===========================================================================
;; PURE: draft → promote-opts + snippet
;; ===========================================================================

(deftest draft->promote-opts-projects-only-set-slots
  (are [draft opts] (= opts (rf.story.ui.promotion/draft->promote-opts draft))
    {:variant-id  :story.x/regression-1
     :doc         "  why this matters  "
     :tags        #{:test :agent}
     :setup-count 1
     :extends     :story.x/source}
    {:variant/id  :story.x/regression-1
     :doc         "why this matters"
     :tags        #{:test :agent}
     :setup-count 1
     :extends     :story.x/source}

    ;; blank / empty / negative slots are dropped; a nil id is kept so an
    ;; unnamed preview can still materialize
    {:variant-id nil :doc "   " :tags #{} :setup-count -1}
    {:variant/id nil}))

(deftest snippet-mirrors-substrate-body
  ;; The previewed snippet reads back to exactly the body the substrate
  ;; registers — `artifact->variant-body`, no reimplemented logic — with the
  ;; draft's setup cut moving the leading step onto :setup.
  (let [art   (sample-artifact)
        draft {:variant-id :story.x/r :tags #{:test} :setup-count 1}
        body  (rf.story.promotion/artifact->variant-body
                art (rf.story.ui.promotion/draft->promote-opts draft))
        pasted (pasted-body (rf.story.ui.promotion/promotion-snippet art draft))]
    (is (= [[:dispatch [:counter/init 5]]] (:setup pasted)))
    (is (= body pasted))))

(deftest promotion-snippet-carries-source-expectations
  ;; a regression an author pastes into source can still fail
  (rf.story.registrar/reg-variant* :story.x/source
    {:script     [[:dispatch [:counter/inc]]]
     :assertions [[:rf.assert/path-equals [:count] 1]]})
  (let [art (rf.story.artifact/make-run-artifact
              {:event-program [[:dispatch [:counter/inc]]]
               :result        {:status :fail :variant/id :story.x/source}})]
    (is (= [[:rf.assert/path-equals [:count] 1]]
           (:assertions (pasted-body (rf.story.ui.promotion/promotion-snippet
                                       art {:variant-id :story.x/regression-1
                                            :extends    :story.x/source})))))))

(deftest promotion-snippet-keeps-composed-check-ids
  ;; a source that failed through a check named in :compose pastes back into
  ;; a variant that still names that check
  (rf.story.registrar/reg-check* :check.x/count-is-one
    {:assertions [[:rf.assert/path-equals [:count] 1]]})
  (rf.story.registrar/reg-variant* :story.x/composed
    {:script  [[:dispatch [:counter/inc]]]
     :compose [:check.x/count-is-one]})
  (let [art           (rf.story.artifact/make-run-artifact
                        {:event-program [[:dispatch [:counter/inc]]]
                         :result        {:status :fail :variant/id :story.x/composed}})
        draft         {:variant-id :story.x/composed-regression
                       :tags       #{:test}
                       :extends    :story.x/composed}
        [_ id pasted] (edn/read-string
                        (rf.story.ui.promotion/promotion-snippet art draft))]
    (is (= [:check.x/count-is-one] (:checks pasted))
        "the carried check id is in the pasted form")
    (rf.story.registrar/reg-variant* id pasted)
    (is (= [:check.x/count-is-one]
           (get-in (rf.story.plan/variant-plan id) [:expect :checks]))
        "the pasted variant resolves the check its source failed through")))

;; ===========================================================================
;; The snippet keeps the run's world: :network and :fx-overrides
;; ===========================================================================
;;
;; The snippet renders only the body keys in its order list, so a slot the
;; list omits is silently absent from what the author pastes. These tests
;; take a REAL run — a compiled plan, coerced by the determinism seam,
;; replayed — through the dialog's own capture rule and snippet, read it back
;; and register it.
;;
;; The draft has no `:extends`: an origin authoring the same world would hand
;; it to the pasted variant through the parent chain and hide the gap.

(defn- replayed-run
  "Compile `body` as the unregistered variant `variant-id`, coerce the plan to
  a run artifact through `determinism/->artifact`, and replay it into a fresh
  frame. The run-result's `:run-artifact` back-link is what the dialog's
  `result->artifact` captures."
  [variant-id body]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.story.artifact/replay-run-artifact
    (rf.story.determinism/->artifact
      (rf.story.plan/variant-plan variant-id {:lookup {variant-id body}}))))

(defn- paste-promotion-snippet!
  "Capture `result` and render its copy-to-source snippet as the dialog does,
  then read the snippet back and register the pasted form. Returns the body
  `promote!` would register, the body read back, and the pasted id."
  [result promoted-id]
  (let [artifact      (rf.story.ui.promotion/result->artifact result [])
        draft         {:variant-id promoted-id :tags #{:test} :setup-count 0}
        [_ id pasted] (edn/read-string
                        (rf.story.ui.promotion/promotion-snippet artifact draft))]
    (rf.story.registrar/reg-variant* id pasted)
    {:body   (rf.story.promotion/artifact->variant-body
               artifact (rf.story.ui.promotion/draft->promote-opts draft))
     :pasted pasted
     :id     id}))

(deftest promotion-snippet-keeps-network-stubs
  (rf/reg-event :promo-snip/get-cart
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request  {:method :get :url "/api/cart"}
                                 :decode   :json
                                 :reply-to [:promo-snip/get-cart msg]}]]})))
  (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
        run    (replayed-run :story.promo-snip/net
                             {:network routes
                              :script  [[:dispatch [:promo-snip/get-cart]]]})
        {:keys [body pasted id]} (paste-promotion-snippet!
                                   run :story.promo-snip/net-regression)]
    (is (= routes (:network body))
        "the promotion path puts the run's route map on the body")
    (is (= body pasted)
        "the snippet reads back to exactly the body promote! registers")
    (is (= routes (get-in (rf.story.plan/variant-plan id) [:world :network]))
        "the pasted variant compiles to the route stubs its run installs")))

(deftest promotion-snippet-keeps-fx-overrides
  (rf/reg-fx :promo-snip/toast {:platforms #{:client :server}} (fn [_ _] nil))
  (rf/reg-fx :promo-snip/toast-stub {:platforms #{:client :server}} (fn [_ _] nil))
  (rf/reg-event :promo-snip/save (fn [_ _] {:fx [[:promo-snip/toast "saved"]]}))
  (let [overrides {:promo-snip/toast :promo-snip/toast-stub}
        run       (replayed-run :story.promo-snip/fx
                                {:fx-overrides overrides
                                 :script       [[:dispatch [:promo-snip/save]]]})
        {:keys [body pasted id]} (paste-promotion-snippet!
                                   run :story.promo-snip/fx-regression)]
    (is (= overrides (:fx-overrides body))
        "the promotion path puts the run's fx decisions on the body")
    (is (= body pasted)
        "the snippet reads back to exactly the body promote! registers")
    (is (= overrides (get-in (rf.story.plan/variant-plan id) [:world :frame :fx-overrides]))
        "the pasted variant compiles to the same fx redirect")))

;; ===========================================================================
;; CLJS-only: the capture store + promote!
;; ===========================================================================

#?(:cljs
   (deftest capture-is-idempotent
     ;; re-capturing the same run is one store entry, not one per re-run
     (let [art (sample-artifact)]
       (rf.story.ui.promotion/capture! art :story.x/v)
       (rf.story.ui.promotion/capture! art :story.x/v)
       (is (= 1 (count (rf.story.ui.promotion/captured-entries)))))))

#?(:cljs
   (deftest promote-registers-and-leaves-artifact-as-evidence
     (let [id  (rf.story.ui.promotion/capture! (sample-artifact) :story.x/v)
           pid (rf.story.ui.promotion/promote! id {:variant-id :story.x/regression-1
                                                   :tags       #{:test}})]
       (is (= :story.x/regression-1 pid)
           "the registered variant id is returned")
       (is (contains? (rf.story.registrar/handler-meta :variant :story.x/regression-1)
                      :run-artifact)
           "registered through the substrate, carrying the source-artifact link")
       (is (contains? @rf.story.ui.promotion/captured-atom id)
           "NON-DESTRUCTIVE — the source artifact stays as evidence"))))

;; ===========================================================================
;; CLJS-only: the dialog
;; ===========================================================================

#?(:cljs
   (deftest dialog-open-seeds-test-tag-and-setup-zero
     ;; a runnable-test default draft that extends its origin variant
     (let [id (rf.story.ui.promotion/capture! (sample-artifact) :story.counter/happy)]
       (rf.story.ui.promotion/open! id)
       (is (= {:tags #{:test} :setup-count 0 :extends :story.counter/happy}
              (select-keys (:draft @rf.story.ui.promotion/dialog-atom)
                           [:tags :setup-count :extends]))))))

#?(:cljs
   (deftest mark-promoted-gates-confirmation-and-open-resets-it
     ;; the confirmation lives on the dialog state, so open! of another
     ;; artifact clears it rather than leaking "Promoted to A" onto B
     (let [art-a (rf.story.artifact/make-run-artifact
                   {:event-program [[:dispatch [:counter/inc]]]
                    :seed          1 :result {:status :fail}})
           art-b (rf.story.artifact/make-run-artifact
                   {:event-program [[:dispatch [:counter/dec]]]
                    :seed          2 :result {:status :fail}})
           id-a  (rf.story.ui.promotion/capture! art-a :story.counter/happy)
           id-b  (rf.story.ui.promotion/capture! art-b :story.counter/happy)
           comp  (rf.story.ui.promotion/promotion-dialog)
           flat  #(str (comp))]
       (rf.story.ui.promotion/open! id-a)
       (rf.story.ui.promotion/mark-promoted! :story.counter/regression-a)
       (is (str/includes? (flat) "story-promotion-confirmation"))
       (is (str/includes? (flat) "regression-a")
           "the confirmation names the promoted id")
       (rf.story.ui.promotion/open! id-b)
       (is (not (str/includes? (flat) "regression-a"))
           "B shows no stale confirmation for A"))))

#?(:cljs
   (deftest dialog-renders-curation-controls-and-snippet-when-open
     (let [id   (rf.story.ui.promotion/capture! (sample-artifact) :story.counter/happy)
           _    (rf.story.ui.promotion/open! id)
           flat (str ((rf.story.ui.promotion/promotion-dialog)))]
       (is (str/includes? flat "story-promotion-snippet"))
       (is (str/includes? flat "story-promotion-curation"))
       (is (str/includes? flat "story-promotion-primary")
           "the primary 'promote' action renders"))))
