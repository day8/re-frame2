(ns re-frame.story.promotion-cljs-test
  "Tests for the run-artifact → variant promotion bridge (rf2-5x1wt.25,
  spec/017-Testing-Story.md §Promotion — Promotion bridge; NewTestStory
  §C1).

  Two layers, both under `clojure -M:test` (JVM) + the node-runtime CLJS
  build:

  - PURE `materialize-variant-plan` (§C1 bullets 1, 2, 4): a run artifact
    becomes a readable normalized plan; the plan preserves the source
    artifact link; the program projects into setup/script per the policy.
    Side-effect-free — these tests assert it registers NOTHING.
  - The explicit `promote-run-artifact!` registration path (§C1 bullet 3):
    promotion does NOT auto-register without the explicit named call; the
    explicit call registers a variant carrying the source link.

  The variant-plan compiler is pure data → data and the registrar is a
  pure side-table, so every test runs on both targets with no host — a
  fresh side-table per test via the fixture, and an explicit `:lookup`
  for `:extends` resolution where needed.

  Named `-cljs-test` so the `:node-test` build's `cljs-test$` ns-regexp
  selects it; under its old `-test` name it ran on the JVM only (rf2-1ep8)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.args :as rf.story.args]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.determinism :as rf.story.determinism]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.promotion :as rf.story.promotion]
            [re-frame.story.registrar :as rf.story.registrar]
            ;; the Test-mode dialog's pure capture + draft helpers — the
            ;; rf2-5vmog tests promote exactly the way the dialog does
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]
            ;; `deref-blocking` is JVM-only; the run-based tests are `:clj`-gated
            #?@(:clj [[re-frame.story.async :as rf.story.async]])))

;; ---- fixtures -----------------------------------------------------------
;;
;; Standard `clojure.test` fixture FUNCTION (not the `{:before …}` map
;; form): a one-arg fn that resets the Story side-table to empty, runs
;; the test, and the promotion path repopulates only what it registers.
;; Matches the `artifact_test` fixture shape — the function form runs on
;; both the JVM `clojure -M:test` runner and the node CLJS build.

;; The headless run-artifact replay test (rf2-87duu) dispatches into a live
;; frame, so the fixture installs the plain-atom adapter + a default frame and
;; clears the epoch surface between tests (mirroring `artifact_test`). The pure
;; materialize/promote tests are unaffected by the extra setup.

(defn reset-side-table! [t]
  (rf.story.registrar/clear-all!)
  (rf.epoch/clear-history!)
  (rf.epoch/clear-epoch-listeners!)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-side-table!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A run artifact with a two-step dispatch program + a stubbed fx
  decision + provenance slots + bulky captured evidence (so the
  provenance-trim assertions have something to drop)."
  []
  (rf.story.artifact/make-run-artifact
    {:event-program [[:dispatch [:counter/init 5]]
                     [:dispatch [:counter/inc]]]
     :seed          42
     :fx-decisions  {:http/get :http/stub}
     :created-at    "2026-05-30T00:00:00Z"
     :source        {:tool :recorder}
     ;; bulky captured evidence the promotion link MUST drop:
     :epoch-tape    [{:big :tape}]
     :trace         [{:big :trace}]
     :result        {:status :fail}}))

;; ===========================================================================
;; §C1 bullet 1 — a run artifact becomes a readable normalized plan
;; ===========================================================================

(deftest materialize-produces-readable-plan
  (testing "a run artifact materializes to the normalized four-bucket plan"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan art)]
      (is (contains? plan :world))
      (is (contains? plan :script))
      (is (contains? plan :expect))
      (is (map? (:expect plan)))
      (is (= #{:client} (get-in plan [:world :platforms]))
          "the plan carries the compiler's normalized defaults")))

  (testing "the default policy projects the whole program into :script"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan art)]
      (is (= [[:dispatch [:counter/init 5]]
              [:dispatch [:counter/inc]]]
             (:script plan)))
      (is (= [] (get-in plan [:world :setup]))
          "nothing is demoted to a silent precondition without a hint")))

  (testing "a :variant/id rides onto the materialized plan"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan
                 art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 (:variant/id plan))))))

;; ===========================================================================
;; §C1 bullet 4 — generated event program becomes script/setup per policy
;; ===========================================================================

(deftest program-projects-to-setup-and-script-per-policy
  (testing ":setup-count cuts preconditions off the front into [:world :setup]"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan art {:setup-count 1})]
      (is (= [[:dispatch [:counter/init 5]]] (get-in plan [:world :setup]))
          "the first step is a precondition")
      (is (= [[:dispatch [:counter/inc]]] (:script plan))
          "the rest is behaviour-under-test")))

  (testing "an explicit :setup + :script partition is used verbatim"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan
                 art {:setup  [[:dispatch [:seed/a]]]
                      :script [[:dispatch [:act/b]]]})]
      (is (= [[:dispatch [:seed/a]]] (get-in plan [:world :setup])))
      (is (= [[:dispatch [:act/b]]] (:script plan)))))

  (testing "partition-program clamps an oversized :setup-count"
    (let [art (sample-artifact)
          {:keys [setup script]} (rf.story.promotion/partition-program art {:setup-count 99})]
      (is (= 2 (count setup)) "every step becomes a precondition")
      (is (= [] script))))

  (testing "a bare event list in :script lifts to a tagged [:dispatch …] program"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan
                 art {:script [[:counter/reset]]})]
      (is (= [[:dispatch [:counter/reset]]] (:script plan))))))

;; ===========================================================================
;; §C1 bullet 2 — promotion preserves the source-artifact link
;; ===========================================================================

(deftest materialize-preserves-source-artifact-link
  (testing "the plan carries a :run-artifact back-link to the source"
    (let [art  (sample-artifact)
          plan (rf.story.promotion/materialize-variant-plan art)
          link (:run-artifact plan)]
      (is (= :rf.test/run-artifact (:artifact/kind link)))
      (is (= 42 (:seed link)))
      (is (= {:http/get :http/stub} (:fx-decisions link)))
      (is (= {:tool :recorder} (:source link)))
      (is (= [[:dispatch [:counter/init 5]]
              [:dispatch [:counter/inc]]]
             (:event-program link))
          "the replayable program survives on the link")))

  (testing "the link is TRIMMED — bulky captured evidence is dropped"
    (let [link (:run-artifact (rf.story.promotion/materialize-variant-plan (sample-artifact)))]
      (is (not (contains? link :epoch-tape)))
      (is (not (contains? link :trace)))
      (is (not (contains? link :result))
          "a registered variant is a curation surface, not an evidence dump")))

  (testing "the promoted variant body also carries the source link"
    (let [body (rf.story.promotion/artifact->variant-body (sample-artifact))]
      (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))))))

;; ===========================================================================
;; §C1 bullet 3 — promotion does NOT auto-register without the explicit call
;; ===========================================================================

(deftest materialize-registers-nothing
  (testing "materialize-variant-plan is pure — it registers NO variant"
    (let [art (sample-artifact)]
      (rf.story.promotion/materialize-variant-plan art {:variant/id :story.counter/never})
      (is (not (rf.story.registrar/registered? :variant :story.counter/never))
          "materialize must not touch the side-table")
      (is (empty? (rf.story.registrar/registrations :variant))
          "the side-table stays empty after materialization"))))

(deftest promote-requires-explicit-variant-id
  (testing "promote-run-artifact! throws without an explicit :variant/id"
    (let [art (sample-artifact)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-id"
            (rf.story.promotion/promote-run-artifact! art {})))
      (is (empty? (rf.story.registrar/registrations :variant))
            "a no-id promotion registers nothing"))))

(deftest promote-honours-only-namespaced-variant-id
  (testing ":variant/id is the SOLE accepted key — the previously-undocumented
            unqualified :variant-id spelling is NOT honoured (symmetric with
            materialize-variant-plan + spec + the rest of the bridge)"
    (let [art (sample-artifact)]
      ;; An opts map carrying ONLY the unqualified :variant-id is treated as
      ;; a no-id promotion: it throws and registers nothing.
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-id"
            (rf.story.promotion/promote-run-artifact! art {:variant-id :story.counter/unqualified})))
      (is (empty? (rf.story.registrar/registrations :variant))
          "the unqualified :variant-id registers nothing"))))

(deftest promotion-refuses-a-missing-artifact
  (testing "promote-run-artifact! refuses a nil or non-artifact exactly as it
            refuses a missing id, and registers nothing. A nil artifact used to
            register a hollow body that ran :pass with zero assertions
            (rf2-vgthk)"
    (doseq [not-an-artifact [nil [[:dispatch [:counter/inc]]]]]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-artifact"
            (rf.story.promotion/promote-run-artifact!
              not-an-artifact {:variant/id :story.counter/hollow}))
          (str "refused: " (pr-str not-an-artifact))))
    (is (empty? (rf.story.registrar/registrations :variant))
        "a refused promotion registers nothing"))
  (testing "materialize-variant-plan refuses the same inputs"
    (doseq [not-an-artifact [nil [[:dispatch [:counter/inc]]]]]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-artifact"
            (rf.story.promotion/materialize-variant-plan not-an-artifact))
          (str "refused: " (pr-str not-an-artifact))))))

(deftest promote-registers-the-named-variant
  (testing "the explicit named call DOES register a curated variant"
    (let [art (sample-artifact)
          ret (rf.story.promotion/promote-run-artifact!
                art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 ret)
          "promote returns the registered variant id")
      (is (rf.story.registrar/registered? :variant :story.counter/regression-042)
          "the variant is now in the side-table")))

  (testing "the registered body carries the source-artifact link + the program"
    (rf.story.registrar/clear-all!)
    (let [art (sample-artifact)]
      (rf.story.promotion/promote-run-artifact!
        art {:variant/id :story.counter/regression-042})
      (let [body (rf.story.registrar/handler-meta :variant :story.counter/regression-042)]
        (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))
            "provenance survives into the registered variant")
        ;; The registrar stores the `:script` bare step-vector verbatim.
        (is (= [[:dispatch [:counter/init 5]]
                [:dispatch [:counter/inc]]]
               (:script body))
            "the behaviour program is the registered play script"))))

  (testing "the facade re-exports route to the same bridge"
    (rf.story.registrar/clear-all!)
    (let [art (sample-artifact)]
      (is (contains? (rf.story/materialize-variant-plan art) :run-artifact))
      (is (= :story.counter/from-facade
             (rf.story/promote-run-artifact!
               art {:variant/id :story.counter/from-facade})))
      (is (rf.story.registrar/registered? :variant :story.counter/from-facade)))))

;; ===========================================================================
;; rf2-87duu — the provenance link of a :network-stubbed run RE-DERIVES it
;; ===========================================================================
;;
;; The whole point of the `:run-artifact` provenance link is re-derivability:
;; the docstring promises the trimmed core is "enough to … re-derive the run".
;; For a run promoted from a `:network`-stubbed run, that link must carry
;; `:network` — the `:fx-decisions` managed-stub REDIRECT
;; (`{:rf.http/managed :rf.http/managed-test-stub}`) survives, but the actual
;; per-route stubs are RE-INSTALLED from the artifact's `:network` map by
;; `with-network-stubs!` / `replay-run-artifact` (rf2-tymyh, artifact.cljc
;; §44-56). Drop `:network` from `provenance-link-keys` and the link replays a
;; DIFFERENT run: every managed request fail-closes on "no stub matched"
;; (`:rf.http/transport`) instead of the recorded `:ok` reply.
;;
;; RED (without `:network` in provenance-link-keys): the link-replayed run's
;;   `:got` is the synthesised "no stub matched" transport FAILURE — a
;;   different run than the one promoted.
;; GREEN (with it): the link round-trips to the SAME run — same matched route,
;;   same recorded `:ok` reply — as a direct replay of the source artifact.

(defn- register-network-event!
  "Register a test event that issues a managed-HTTP request to `route`
  ([method url]) and records the reply into app-db under `:got` (the reply
  rides back to this same origin event via `:reply-to`, Spec 014 §Reply
  addressing — appended as the last arg). Mirrors the artifact_test helper
  so the round-trip exercises the same managed-HTTP fail-close path."
  [event-id [method url]]
  (rf/reg-event event-id
    (fn [{:keys [db]} [_ msg reply]]
      (if reply
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request {:method method :url url}
                                 :decode  :json
                                 :reply-to [event-id msg]}]]}))))

(defn- network-artifact
  "Compile a `:network` variant plan for `routes` and coerce it through the
  determinism gate's `->artifact` (the real materialize-to-artifact seam),
  so the artifact carries both the `:network` route map and the
  `:fx-decisions` managed-stub redirect — exactly what a recorded HTTP run
  produces."
  [routes script]
  (let [variant-id :story.promo-net/v
        plan       (rf.story.plan/variant-plan
                     variant-id
                     {:lookup {variant-id {:network routes
                                           :script  script}}})]
    (rf.story.determinism/->artifact plan)))

(deftest promotion-link-of-network-run-re-derives-it
  (testing "a variant promoted from a :network-stubbed run carries a
            provenance link that ROUND-TRIPS through replay-run-artifact to
            the SAME run (rf2-87duu — :network is load-bearing for replay)"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          ;; the trimmed provenance link a promotion stores on :run-artifact
          link   (rf.story.promotion/provenance-link art)]

      ;; The link must itself carry the network route map — it is the slot
      ;; replay re-installs the per-route stubs from. (RED without the fix.)
      (is (= routes (:network link))
          "the provenance link preserves :network so replay can re-install
           the route stubs (rf2-87duu — same class as rf2-tymyh)")

      ;; A direct replay of the SOURCE artifact: the route matches and the
      ;; recorded :ok reply is synthesised. This is the run that was promoted.
      (let [src (rf.story.artifact/replay-run-artifact art)]
        (is (= :pass (:status src)))
        (is (= :ok (:status (:got (:app-db src))))
            "the source run matched the route stub"))

      ;; Re-deriving from the LINK alone must reproduce the SAME run — the
      ;; provenance link is replayable on its own (it carries :artifact/kind,
      ;; :event-program, :fx-decisions, and — post-fix — :network). Without
      ;; :network the managed request fail-closes on "no stub matched"
      ;; (:rf.http/transport), a DIFFERENT run.
      (let [from-link (rf.story.artifact/replay-run-artifact link)
            got       (:got (:app-db from-link))]
        (is (= :pass (:status from-link))
            "the link re-derives a passing run — NOT a fail-closed one")
        (is (= :ok (:status got))
            "the re-installed route stub matched on the LINK replay — NOT the
             'no stub matched' transport failure that fail-closes without
             :network in provenance-link-keys")
        (is (= {:items [{:sku "A"}]} (:value got))
            "the link round-trips to the SAME recorded reply as the source run")))))

;; ===========================================================================
;; rf2-vf8es — the promoted VARIANT BODY carries the runnable :network +
;; :fx-decisions, so running the variant reproduces the run (NOT just the
;; provenance-link replay rf2-87duu covers)
;; ===========================================================================
;;
;; rf2-87duu preserved :network on the provenance LINK so `replay-run-artifact`
;; re-derives the run FROM THE ARTIFACT. But the whole point of promotion is to
;; RUN THE VARIANT — `artifact->variant-body` builds the body the registrar
;; stores and the runner executes. Before this fix the body carried only the
;; program (:setup/:script) + the link, so the registered variant's
;; [:world :network] / [:world :frame :fx-overrides] were EMPTY: run normally a
;; managed HTTP request fail-closed ("no stub matched"), a SILENT fidelity gap.
;;
;; These tests pin the runnable contract on the VARIANT BODY:
;;   1. the body carries :network + :fx-overrides (the runnable slots, NOT just
;;      the :run-artifact link);
;;   2. compiling the body populates [:world :network] (so the run installs the
;;      route stubs) + lowers :rf.http/managed to the managed-stub fx;
;;   3. a full round-trip — body → plan → ->artifact → replay — reproduces the
;;      SAME :success reply as a direct replay of the source artifact. RED
;;      (pre-fix, body without :network): the round-trip artifact's :network is
;;      empty and the request fail-closes. GREEN: it reproduces.

(deftest promoted-variant-body-carries-runnable-network-and-fx
  (testing "the variant body lifts the artifact's :network + :fx-decisions onto
            the RUNNABLE :network / :fx-overrides slots (rf2-vf8es)"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          body   (rf.story.promotion/artifact->variant-body art)]
      (is (= routes (:network body))
          "the per-route reply map rides the body's runnable :network slot")
      ;; The artifact's :fx-decisions carries the lowered managed-stub redirect;
      ;; the body's :network slot OWNS :rf.http/managed (it re-derives the same
      ;; redirect through rf.story.plan/lower-network), so the lifted :fx-overrides must
      ;; NOT also set it — else check-network-fx-conflict! hard-fails.
      (is (not (contains? (:fx-overrides body) rf.story.plan/managed-fx-id))
          ":rf.http/managed is dropped from :fx-overrides — :network owns it"))))

(deftest promoted-variant-body-compiles-to-installed-network
  (testing "compiling the promoted body keeps the routes at [:world :network]
            (so the run installs the stubs) + lowers :rf.http/managed to the
            managed-stub fx — NOT an empty network that fail-closes (rf2-vf8es)"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          body   (rf.story.promotion/artifact->variant-body art)
          ;; compile the body as an inline plan target (read-only, no register)
          plan   (rf.story.plan/variant-plan body)]
      (is (= routes (get-in plan [:world :network]))
          "the compiled plan keeps the route map at [:world :network]")
      (is (= rf.story.plan/managed-stub-fx-id
             (get-in plan [:world :frame :fx-overrides rf.story.plan/managed-fx-id]))
          ":rf.http/managed is lowered to the managed-stub fx the runner installs"))))

(deftest promoted-network-variant-runs-to-the-same-result
  (testing "RED→GREEN (rf2-vf8es): running the PROMOTED VARIANT reproduces the
            source run's :success reply. The body → plan → ->artifact → replay
            round-trip re-installs the route stubs from the body's :network slot;
            without the fix the body has no :network, the round-trip artifact's
            :network is empty, and the managed request fail-closes ('no stub
            matched') — a DIFFERENT run."
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          ;; the run the variant was promoted FROM (the source artifact replay).
          src    (rf.story.artifact/replay-run-artifact art)
          ;; the PROMOTED VARIANT: body → compiled plan → run-artifact. Running
          ;; the variant = compiling its body + executing it; ->artifact is the
          ;; real materialize-to-run seam, and replay re-installs the body's
          ;; :network route stubs (with-network-stubs!).
          body   (rf.story.promotion/artifact->variant-body art)
          plan   (rf.story.plan/variant-plan body)
          var-art (rf.story.determinism/->artifact plan)
          ran    (rf.story.artifact/replay-run-artifact var-art)]
      (is (= routes (:network var-art))
          "the promoted variant's run-artifact carries the route map (NOT empty)")
      (is (= (:status src) (:status ran) :pass)
          "the promoted variant runs to the SAME status as the source run")
      ;; The canonical reply envelope (rf2-ibksxg) carries `:rf.frame/id`,
      ;; which is a fresh per-replay-run frame id — so compare the replies
      ;; MODULO that run-specific stamp; the value/status/work-id are what
      ;; "the same recorded reply" means here.
      (is (= (dissoc (:got (:app-db src)) :rf.frame/id)
             (dissoc (:got (:app-db ran)) :rf.frame/id))
          "the promoted variant reproduces the SAME recorded reply")
      (is (= :ok (:status (:got (:app-db ran))))
          "the route stub matched on the promoted-variant run — NOT a
           fail-closed 'no stub matched' transport failure"))))

;; ===========================================================================
;; rf2-5vmog — a promoted regression fails for the reason its source failed
;; ===========================================================================
;;
;; A run artifact records a program, not a judgement. Before the fix
;; `artifact->variant-body` copied neither the source variant's terminal
;; `:assertions` nor its `:checks`, so a source that ran `:fail` promoted
;; into a variant that ran `:pass` with ZERO assertions. Test mode's capture
;; (`result->artifact` over the dispatch-only `variant-play-events`) also
;; dropped every `:script` step that is not a dispatch, so an in-script
;; `[:assert …]` checkpoint vanished the same way.
;;
;; The acceptance is fail/pass/fail against the APP: the promoted variant
;; fails under the original fault with the SAME assertion count as its
;; source, passes BY that assertion once the handler is fixed, and fails
;; again when the fault is restored. Two promotion routes:
;;   - DIALOG — the Test-mode dialog's own capture helper and default draft
;;     (`:extends` the origin, `:setup-count 0`, `#{:test}`);
;;   - API — spec/017's own example: a plan-derived artifact promoted with
;;     nothing but a `:variant/id`.
;; And two expectation positions, because the measured boundary lies between
;; them:
;;   - DECLARATIVE — `:assertions` beside the program (lost on both routes);
;;   - IN-PROGRAM — an `[:assert …]` checkpoint inside `:script` (survived the
;;     API route, whose artifact keeps the whole program; lost on the dialog
;;     route's dispatch-only capture).
;; Then two SETUP-bearing shapes whose `:script` dispatches nothing (rf2-vgthk):
;; a `:setup` precondition with declarative `:assertions` and no `:script`, and
;; the login_form testbed's `:setup` plus `[:assert …]`-only `:script`. The
;; dialog's dispatch-only capture of either is EMPTY, so it used to capture
;; nothing, and the promotion registered a hollow body that ran `:pass` with
;; zero assertions. Their `:setup` reaches the promoted variant through the
;; draft's `:extends`, exactly as it does for a dispatch-bearing source.
;; Last, a check named in `:compose` (rf2-6h2z3), in a dispatching and a
;; dispatch-free source. `:compose` is child-only, so no `:extends` recovers
;; the check and no artifact records it: the promotion has to carry the
;; source's resolved check ids, so these pin the check count too.
;; And a checkpoint that reads a RUN INPUT (rf2-cml0h). Test mode runs a
;; variant with the controls panel's `:cell-overrides` and the chrome's
;; `:active-modes`, so a checkpoint's `[:arg]` can be supplied only by the run,
;; or overridden by it. Capture and promotion must compile the source with
;; those inputs: otherwise a required input fails the compile and nothing is
;; captured, and an overridden default promotes the default, not the value
;; that ran. The same input can reach the promoted variant through a setup it
;; INHERITS (rf2-rky08): the dialog's draft `:extends` the source, whose
;; `:setup` re-substitutes its `[:arg]` when the promoted variant compiles, so
;; the promotion carries the run's inputs as its own `:args`.

#?(:clj
   (defn- reg-inc!
     "The app under test. `fixed?` false is the FAULT — `:promo/inc` never
     moves the counter; true is the repair."
     [fixed?]
     (rf/reg-event :promo/inc
       (fn [{:keys [db]} _]
         {:db (if fixed? (update db :n (fnil inc 0)) (assoc db :n 0))}))))

#?(:clj
   (defn- dialog-promote!
     "Promote `source-id`'s run `result` the way the Test-mode dialog does:
     its capture over the dispatch-only play-events, then its default draft."
     [source-id result promoted-id]
     (rf.story.promotion/promote-run-artifact!
       (rf.story.ui.promotion/result->artifact
         result (rf.story.play/variant-play-events source-id))
       (rf.story.ui.promotion/draft->promote-opts
         {:variant-id promoted-id :tags #{:test} :setup-count 0 :extends source-id}))))

#?(:clj
   (defn- api-promote!
     "Promote a plan-derived artifact of `source-id` with only a `:variant/id`."
     [source-id promoted-id]
     (rf.story.promotion/promote-run-artifact!
       (rf.story.determinism/->artifact (rf.story.plan/variant-plan source-id))
       {:variant/id promoted-id})))

#?(:clj
   (defn- verdict
     "What a regression is judged by: its status and how many assertion and
     check records it produced."
     [result]
     {:status     (:status result)
      :assertions (count (:assertions result))
      :checks     (count (:checks result))}))

#?(:clj
   (defn- run-verdict
     "Run variant `id` headless and keep what a regression is judged by."
     [id]
     (let [result (rf.story.async/deref-blocking (rf.story/run id) 10000)]
       (verdict result))))

#?(:clj
   (defn- fault-fix-fault
     "Run `id` under the fault, then against the fixed app, then under the
     restored fault. `reg-app!` installs the app, faulty when given false; it
     defaults to `reg-inc!`."
     ([id] (fault-fix-fault id reg-inc!))
     ([id reg-app!]
      (reg-app! false)
      (let [faulty (run-verdict id)]
        (reg-app! true)
        (let [fixed (run-verdict id)]
          (reg-app! false)
          [faulty fixed (run-verdict id)])))))

#?(:clj
   (defn- assert-promotions-fail-pass-fail
     "Register `source-body` under `source-id`, check it fails on its one
     assertion and its `checks` check records (0 unless its verdict comes
     from a check), promote it by both routes, and check each promotion runs
     fail / pass / fail with the same counts throughout."
     ([source-id source-body] (assert-promotions-fail-pass-fail source-id source-body 0))
     ([source-id source-body checks]
      (rf.story/install-canonical-vocabulary!)
      (reg-inc! false)
      (rf.story.registrar/reg-variant* source-id source-body)
      (let [result   (rf.story.async/deref-blocking (rf.story/run source-id) 10000)
            dialog   (keyword (namespace source-id) (str (name source-id) "-dialog"))
            api      (keyword (namespace source-id) (str (name source-id) "-api"))
            failing  {:status :fail :assertions 1 :checks checks}]
        (is (= failing (verdict result))
            "the source fails on its one assertion under the fault")
        (dialog-promote! source-id result dialog)
        (api-promote! source-id api)
        (doseq [id [dialog api]]
          (is (= [failing {:status :pass :assertions 1 :checks checks} failing]
                 (fault-fix-fault id))
              (str id " runs fail / pass / fail with its source's one assertion")))))))

#?(:clj
   (deftest promoted-regression-keeps-its-declarative-expectation
     (testing "a source failing on a DECLARATIVE :assertions entry promotes, by
               the dialog route and the API route, into a variant that fails
               with the same assertion count, passes BY that assertion once the
               app is fixed, and fails when the fault returns (rf2-5vmog)"
       (assert-promotions-fail-pass-fail
         :story.promo/declared
         {:tags       #{:test}
          :script     [[:dispatch [:promo/inc]]]
          :assertions [[:rf.assert/path-equals [:n] 1]]}))))

#?(:clj
   (deftest promoted-regression-keeps-its-in-script-checkpoint
     (testing "an [:assert …] checkpoint INSIDE the program survives both routes:
               the API artifact retains the whole program (the positive control
               — this held before the fix), and the dialog's dispatch-only
               capture is now replaced by the source's full program (rf2-5vmog)"
       (assert-promotions-fail-pass-fail
         :story.promo/checkpoint
         {:tags   #{:test}
          :script [[:dispatch [:promo/inc]]
                   [:assert [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-dispatched-assertion-event
     (testing "REGRESSION GUARD — the one shape that survived before the fix: an
               :rf.assert/* event DISPATCHED by the program rides :event-program
               on both routes. The source program is all dispatches, so nothing
               is replaced and nothing is carried; it must still run
               fail / pass / fail (rf2-5vmog)"
       (assert-promotions-fail-pass-fail
         :story.promo/dispatched
         {:tags   #{:test}
          :script [[:dispatch [:promo/inc]]
                   [:dispatch [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-setup-only-source-expectation
     (testing "a source with a :setup precondition, a declarative :assertions
               entry and NO :script dispatches nothing, so the dialog's
               dispatch-only capture is empty. Both routes must still run
               fail / pass / fail with the source's one assertion (rf2-vgthk)"
       (assert-promotions-fail-pass-fail
         :story.promo/setup-declared
         {:tags       #{:test}
          :setup      [[:promo/inc]]
          :assertions [[:rf.assert/path-equals [:n] 1]]}))))

#?(:clj
   (deftest promoted-regression-keeps-a-checkpoint-only-script
     (testing "the login_form testbed's shape: a :setup precondition and a
               :script of [:assert …] checkpoints only. The script dispatches
               nothing, and the checkpoint must still survive both routes
               (rf2-vgthk)"
       (assert-promotions-fail-pass-fail
         :story.promo/setup-checkpoint
         {:tags   #{:test}
          :setup  [[:promo/inc]]
          :script [[:assert [:rf.assert/path-equals [:n] 1]]]}))))

#?(:clj
   (defn- reg-n-is-one-check!
     "The registered check a composed-check source fails through: the same
     atom the direct-assertion regressions declare inline."
     []
     (rf.story.registrar/reg-check* :check.promo/n-is-one
       {:assertions [[:rf.assert/path-equals [:n] 1]]})))

#?(:clj
   (deftest promoted-regression-keeps-its-composed-check
     (testing "a source whose verdict comes from a check named in :compose
               promotes, by the dialog route and the API route, into a variant
               that fails with the same assertion and check counts, passes BY
               that check once the app is fixed, and fails when the fault
               returns. :compose is child-only, so even the dialog draft's
               :extends cannot recover the check (rf2-6h2z3)"
       (reg-n-is-one-check!)
       (assert-promotions-fail-pass-fail
         :story.promo/composed
         {:tags    #{:test}
          :script  [[:dispatch [:promo/inc]]]
          :compose [:check.promo/n-is-one]}
         1))))

#?(:clj
   (deftest promoted-regression-keeps-a-dispatch-free-composed-check
     (testing "the dispatch-free shape Test mode captures from the source's
               stepped program (rf2-vgthk): a :setup precondition, a composed
               check and no :script. The capture is empty, so the check reaches
               the promoted variant only by being carried (rf2-6h2z3)"
       (reg-n-is-one-check!)
       (assert-promotions-fail-pass-fail
         :story.promo/setup-composed
         {:tags    #{:test}
          :setup   [[:promo/inc]]
          :compose [:check.promo/n-is-one]}
         1))))

#?(:clj
   (defn- reg-boot!
     "The app under test for a run-input checkpoint. `fixed?` false is the
     FAULT — `:promo/boot` seeds `:n` 1 where the run expects 2."
     [fixed?]
     (rf/reg-event :promo/boot
       (fn [{:keys [db]} _]
         {:db (assoc db :n (if fixed? 2 1))}))))

#?(:clj
   (defn- assert-run-input-promotion-fail-pass-fail
     "Run `source-body` under `source-id` with the Test-mode `run-opts`, capture
     the failing run and promote it the way the dialog does, and check the
     capture holds the checkpoint value the run asserted (2) and the promotion
     runs fail / pass / fail with its source's one assertion."
     [source-id source-body run-opts]
     (rf.story/install-canonical-vocabulary!)
     (reg-boot! false)
     (rf.story.registrar/reg-variant* source-id source-body)
     (let [result      (rf.story.async/deref-blocking (rf.story/run source-id run-opts) 10000)
           play-events (rf.story.play/variant-play-events source-id run-opts)
           capture     (rf.story.ui.promotion/result->artifact result play-events run-opts)
           promoted    (keyword (namespace source-id) (str (name source-id) "-dialog"))
           failing     {:status :fail :assertions 1 :checks 0}]
       (is (= failing (verdict result))
           "the source fails on its one checkpoint under the fault")
       (is (= [] play-events)
           "precondition: the script dispatches nothing")
       (when (is (some? capture) "the run is capturable")
         (is (= [[:assert [:rf.assert/path-equals [:n] 2]]] (:event-program capture))
             "the capture asserts the value the run asserted")
         (rf.story.promotion/promote-run-artifact!
           capture
           (rf.story.ui.promotion/draft->promote-opts
             {:variant-id promoted :tags #{:test} :setup-count 0 :extends source-id}))
         (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                (fault-fix-fault promoted reg-boot!))
             (str promoted " runs fail / pass / fail with its source's one assertion"))))))

#?(:clj
   (deftest promoted-regression-keeps-a-required-run-input
     (testing "a checkpoint-only source whose [:arg] has NO default, run with
               the :cell-overrides that supply it: capture compiles the source
               with the run's inputs, so it is available rather than nil, and
               the promotion runs fail / pass / fail (rf2-cml0h)"
       (assert-run-input-promotion-fail-pass-fail
         :story.promo/required-input
         {:tags   #{:test}
          :setup  [[:promo/boot]]
          :script [[:assert [:rf.assert/path-equals [:n] [:arg :expected]]]]}
         {:cell-overrides {:expected 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-an-overridden-run-input
     (testing "a checkpoint-only source whose [:arg] defaults to 1, run with
               :cell-overrides {:expected 2}: the capture asserts the executed
               2, so the dialog's default promotion of the failing run still
               fails against the unchanged app, then passes and fails as the
               boot handler is fixed and refaulted (rf2-cml0h)"
       (assert-run-input-promotion-fail-pass-fail
         :story.promo/overridden-input
         {:tags   #{:test}
          :args   {:expected 1}
          :setup  [[:promo/boot]]
          :script [[:assert [:rf.assert/path-equals [:n] [:arg :expected]]]]}
         {:cell-overrides {:expected 2}}))))

#?(:clj
   (def ^:private seeds
     "How many times `:promo/seed` has run — the setup-once pin."
     (atom 0)))

#?(:clj
   (defn- reg-seed!
     "The app under test for a run input its SETUP reads. `:promo/seed` takes a
     quantity and records whether the app accepts it. `fixed?` false is the
     FAULT — only the source's default quantity, 1, is accepted; true is the
     repair."
     [fixed?]
     (rf/reg-event :promo/seed
       (fn [{:keys [db]} [_ qty]]
         (swap! seeds inc)
         {:db (assoc db :accepted? (if fixed? (pos? qty) (= 1 qty)))}))))

#?(:clj
   (defn- assert-setup-input-promotion-fail-pass-fail
     "Run `source-body` under `source-id` with the Test-mode `run-opts`, whose
     input only the source's `:setup` reads, capture the failing run and promote
     it with the dialog's default draft, which `:extends` the source and so
     inherits that setup. Run with NO opts, the promoted variant must fail as
     its source did, pass once the app is fixed and fail when the fault returns,
     with its source's one assertion. Run with the source's opts it fails too
     (the positive control), and its inherited setup runs once per run."
     [source-id source-body run-opts]
     (rf.story/install-canonical-vocabulary!)
     (reg-seed! false)
     (rf.story.registrar/reg-variant* source-id source-body)
     (let [result   (rf.story.async/deref-blocking (rf.story/run source-id run-opts) 10000)
           capture  (rf.story.ui.promotion/result->artifact
                      result (rf.story.play/variant-play-events source-id run-opts) run-opts)
           promoted (keyword (namespace source-id) (str (name source-id) "-dialog"))
           failing  {:status :fail :assertions 1 :checks 0}]
       (is (= failing (verdict result))
           "the source fails on its one checkpoint under the run's input")
       (when (is (some? capture) "the run is capturable")
         (rf.story.promotion/promote-run-artifact!
           capture
           (rf.story.ui.promotion/draft->promote-opts
             {:variant-id promoted :tags #{:test} :setup-count 0 :extends source-id}))
         (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                (fault-fix-fault promoted reg-seed!))
             (str promoted ", run with no opts, runs fail / pass / fail with its source's one assertion"))
         (is (= failing
                (verdict (rf.story.async/deref-blocking (rf.story/run promoted run-opts) 10000)))
             "positive control: run with the source's opts, the promoted variant fails")
         (reset! seeds 0)
         (is (= failing (run-verdict promoted)))
         (is (= 1 @seeds) "the inherited setup runs once per run")))))

#?(:clj
   (deftest promoted-regression-keeps-an-overridden-input-its-setup-reads
     (testing "the audit's shape: the source's :setup reads [:arg :qty], which
               defaults to 1, and the run overrides it to 2 through
               :cell-overrides. The checkpoint reads no input, so the capture is
               the same under any input. The promoted variant :extends the
               source and inherits that setup, so it must run it with the 2
               that failed, not the default that passes against the unchanged
               app (rf2-rky08)"
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-overridden-input
         {:tags   #{:test}
          :args   {:qty 1}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:cell-overrides {:qty 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-a-required-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty] with NO default, supplied
               only by the run's :cell-overrides: the promoted variant must
               still compile and run it with the input that ran (rf2-rky08)"
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-required-input
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:cell-overrides {:qty 2}}))))

#?(:clj
   (deftest promoted-regression-keeps-a-mode-supplied-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty], supplied only by an
               active mode: the promoted variant runs with no modes, so it must
               carry the input that ran (rf2-rky08)"
       (rf.story.registrar/reg-mode* :Mode.promo/qty-two {:args {:qty 2}})
       (assert-setup-input-promotion-fail-pass-fail
         :story.promo/setup-mode-input
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:active-modes [:Mode.promo/qty-two]}))))

(deftest promotion-carries-the-run-inputs-at-the-values-that-ran
  (testing "the promoted body's :args carry exactly the keys the run's modes and
            cell overrides supplied, at the values the source resolved under the
            ordinary precedence, so the setup the promotion inherits through
            :extends is the setup the run executed (rf2-rky08)"
    (rf.story.registrar/reg-mode* :Mode.promo/qty-three {:args {:qty 3 :note "mode"}})
    (rf.story.registrar/reg-variant* :story.promo/seeded
      {:args   {:qty 1 :note "variant" :label "default"}
       :setup  [[:promo/seed [:arg :qty]]]
       :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]})
    (let [run-opts {:active-modes [:Mode.promo/qty-three] :cell-overrides {:qty 2}}
          capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/seeded} [] run-opts)
          draft    {:extends :story.promo/seeded}
          ran      (rf.story.plan/variant-plan
                     :story.promo/seeded
                     {:run-args (rf.story.args/run-arg-layers :story.promo/seeded run-opts)})]
      (is (= {:qty 2 :note "variant"}
             (:args (rf.story.promotion/artifact->variant-body capture draft)))
          "the cell override beats the mode for :qty, the source's own :note
           beats the mode, and :label, which no run input named, is not carried")
      (is (= (get-in ran [:world :setup])
             (get-in (rf.story.promotion/materialize-variant-plan capture draft) [:world :setup]))
          "the promoted plan's inherited setup is the setup the run executed")
      (is (= {:qty 5 :note "variant"}
             (:args (rf.story.promotion/artifact->variant-body
                      capture (assoc draft :args {:qty 5}))))
          "an explicit :args opt still wins over a carried input")
      (is (not (contains? (rf.story.promotion/artifact->variant-body
                            (rf.story.ui.promotion/result->artifact
                              {:status :fail :variant/id :story.promo/seeded} [])
                            draft)
                          :args))
          "a capture that recorded no run inputs carries no :args"))))

(deftest promotion-compiles-the-source-with-the-captured-run-inputs
  (testing "promotion compiles the source with the run inputs a Test-mode
            capture recorded, so a dispatch-only capture whose dispatch carries
            a run-only [:arg] is still replaced by the source's full program,
            and a composed check is still carried although the source does not
            compile without that input (rf2-cml0h)"
    (rf.story.registrar/reg-check* :check.promo/n-is-two
      {:assertions [[:rf.assert/path-equals [:n] 2]]})
    (rf.story.registrar/reg-variant* :story.promo/input-driven
      {:script  [[:dispatch [:promo/set [:arg :n]]]
                 [:assert [:rf.assert/path-equals [:n] [:arg :n]]]]
       :compose [:check.promo/n-is-two]})
    (let [run-opts {:cell-overrides {:n 2}}
          capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/input-driven}
                     (rf.story.play/variant-play-events :story.promo/input-driven run-opts)
                     run-opts)
          body     (rf.story.promotion/artifact->variant-body
                     capture {:extends :story.promo/input-driven})]
      (is (= [[:dispatch [:promo/set 2]]] (:event-program capture))
          "precondition: Test mode captures the dispatch that ran")
      (is (= [[:dispatch [:promo/set 2]]
              [:assert [:rf.assert/path-equals [:n] 2]]]
             (:script body))
          "the source's full program, compiled with the run's inputs")
      (is (= [:check.promo/n-is-two] (:checks body))
          "the composed check, resolved from the source compiled with the run's inputs"))))

(deftest ordinary-extends-inheritance-is-unchanged
  (testing "a plain :extends child still gets NO terminal assertions from its
            parent — promotion carries them, inheritance deliberately does not"
    (rf.story.registrar/reg-variant* :story.promo/parent
      {:script     [[:dispatch [:promo/inc]]]
       :assertions [[:rf.assert/path-equals [:n] 1]]})
    (rf.story.registrar/reg-variant* :story.promo/plain-child
      {:extends :story.promo/parent})
    (is (= [[:rf.assert/path-equals [:n] 1]]
           (get-in (rf.story.plan/variant-plan :story.promo/parent) [:expect :assertions])))
    (is (= [] (get-in (rf.story.plan/variant-plan :story.promo/plain-child)
                      [:expect :assertions])))))

(deftest only-the-recorded-source-variant-is-carried
  (rf.story.registrar/reg-variant* :story.promo/recorded
    {:script     [[:dispatch [:promo/inc]]]
     :assertions [[:rf.assert/path-equals [:n] 1]]})
  (let [program [[:dispatch [:promo/inc]]]]
    (testing "the source is read off the artifact: [:result :variant/id] (a
              Test-mode capture) or [:source :variant/id] (a plan-derived one)"
      (doseq [art [(rf.story.artifact/make-run-artifact
                     {:event-program program
                      :result        {:status :fail :variant/id :story.promo/recorded}})
                   (rf.story.artifact/make-run-artifact
                     {:event-program program
                      :source        {:tool :determinism-gate :variant/id :story.promo/recorded}})]]
        (is (= :story.promo/recorded (rf.story.promotion/source-variant-id art)))
        (is (= [[:rf.assert/path-equals [:n] 1]]
               (:assertions (rf.story.promotion/artifact->variant-body art))))
        (is (= :story.promo/recorded
               (:extends (rf.story.promotion/artifact->variant-body art)))
            "with no :extends given, the registered source is extended (rf2-hyheo)")))
    (testing "an :extends parent is NOT a source — an artifact that records no
              source is promoted exactly as captured"
      (let [body (rf.story.promotion/artifact->variant-body
                   (rf.story.artifact/make-run-artifact {:event-program program})
                   {:extends :story.promo/recorded})]
        (is (not (contains? body :assertions)))
        (is (= program (:script body)))))
    (testing "a recorded source that is not registered carries nothing"
      (let [body (rf.story.promotion/artifact->variant-body
                   (rf.story.artifact/make-run-artifact
                     {:event-program program
                      :result        {:variant/id :story.promo/never-registered}}))]
        (is (not (contains? body :assertions)))
        (is (not (contains? body :extends))
            "an unregistered source has nothing to extend (rf2-hyheo)")
        (is (= program (:script body)))))))

(deftest source-expectations-carries-own-assertions-and-checks
  (is (= {:assertions [[:rf.assert/path-equals [:n] 1]]
          :checks     [:story.promo/some-check]}
         (rf.story.promotion/source-expectations
           {:script     [[:dispatch [:promo/inc]]]
            :checks     [:story.promo/some-check]
            :assertions [[:rf.assert/path-equals [:n] 1]]})))
  (is (= {} (rf.story.promotion/source-expectations {:script [[:dispatch [:promo/inc]]]}))
      "empty slots are omitted, so a body without expectations gains no keys")
  (is (= {} (rf.story.promotion/source-expectations nil))))

(deftest promotion-carries-the-resolved-checks-once
  (testing "the carried :checks come from the compiler's resolution of the
            source (inherited, own and composed, each id once), so the promoted
            plan resolves the same checks as its source whether or not it
            :extends that source (rf2-6h2z3)"
    (doseq [cid [:check.promo/inherited :check.promo/own :check.promo/composed]]
      (rf.story.registrar/reg-check* cid {:assertions [[:rf.assert/path-equals [:n] 1]]}))
    (rf.story.registrar/reg-variant* :story.promo/checked-parent
      {:checks [:check.promo/inherited]})
    (rf.story.registrar/reg-variant* :story.promo/checked
      {:extends :story.promo/checked-parent
       :script  [[:dispatch [:promo/inc]]]
       :checks  [:check.promo/own]
       :compose [:check.promo/composed :check.promo/own]})
    (let [resolved  [:check.promo/inherited :check.promo/own :check.promo/composed]
          art       (rf.story.determinism/->artifact
                      (rf.story.plan/variant-plan :story.promo/checked))
          checks-of (fn [body]
                      (get-in (rf.story.plan/variant-plan
                                (assoc body :variant/id :story.promo/checked-promoted))
                              [:expect :checks]))]
      (is (= resolved (get-in (rf.story.plan/variant-plan :story.promo/checked)
                              [:expect :checks]))
          "control: this is how the compiler resolves the source's checks")
      (testing "extending a variant other than the source, the inherited check
                is retained and the id named in both :checks and :compose is
                carried once"
        (let [body (rf.story.promotion/artifact->variant-body
                     art {:extends :story.promo/checked-parent})]
          (is (= :story.promo/checked-parent (:extends body))
              "an explicit :extends wins over the default")
          (is (= resolved (:checks body)))
          (is (= resolved (checks-of body)))))
      (testing "with :extends of the source, given or defaulted (rf2-hyheo),
                inheritance supplies the chain's checks, so the body adds beside
                the source's own only what :extends cannot recover"
        (doseq [opts [{:extends :story.promo/checked} nil]]
          (let [body (rf.story.promotion/artifact->variant-body art opts)]
            (is (= :story.promo/checked (:extends body)))
            (is (= [:check.promo/own :check.promo/composed] (:checks body)))
            (is (= resolved (checks-of body)))))))))

(def ^:private dom-script
  "A DOM-driven play: typing, a click, a wait and two checkpoints, behind one
  dispatch (Test mode offers promotion only for a run with a dispatch)."
  [[:dispatch [:promo/open]]
   [:type "[data-test=promo-name]" "Ada"]
   [:click "[data-test=promo-save]"]
   [:wait 20]
   [:assert-dom "[data-test=promo-name]" :visible]
   [:assert [:rf.assert/path-equals [:saved] "Ada"]]])

(deftest promoted-dom-driven-variant-retains-its-script
  (testing "the dialog's dispatch-only capture of a DOM-driven variant promotes
            into a body carrying the source's FULL step program, so the promoted
            plan types, clicks, waits and asserts exactly as its source does and
            declares the same expectations and the same DOM runner requirement.
            The browser suite `re-frame.story.promotion-dom-cljs-test` runs it
            fail / pass / fail (rf2-5vmog)"
    (rf.story.registrar/install-canonical-tags!)
    (rf.story.registrar/reg-variant* :story.promo/dom
      {:tags       #{:test}
       :script     dom-script
       :assertions [[:rf.assert/path-equals [:saved] "Ada"]]})
    (let [capture  (rf.story.ui.promotion/result->artifact
                     {:status :fail :variant/id :story.promo/dom}
                     (rf.story.play/variant-play-events :story.promo/dom))
          body     (rf.story.promotion/artifact->variant-body
                     capture
                     (rf.story.ui.promotion/draft->promote-opts
                       {:variant-id  :story.promo/dom-promoted
                        :tags        #{:test}
                        :setup-count 0
                        :extends     :story.promo/dom}))
          source   (rf.story.plan/variant-plan :story.promo/dom)
          promoted (rf.story.plan/variant-plan
                     (assoc body :variant/id :story.promo/dom-promoted))]
      (is (= [[:dispatch [:promo/open]]] (:event-program capture))
          "Test mode captures only the dispatch — typing, click, wait and both
           checkpoints are absent from the artifact itself")
      (is (= (rf.story.play/variant-play-steps :story.promo/dom) (:script body))
          "the promoted body carries the source's full step program, in order")
      (is (= (:script source) (:script promoted)))
      (is (= (:expect source) (:expect promoted))
          "the same checks and the same terminal assertions")
      (is (= (:required-runner source) (:required-runner promoted)))
      (is (contains? (:required-runner promoted) :dom)
          "the promoted variant is still DOM-driven, not a flattened event replay"))))

;; ===========================================================================
;; rf2-hyheo — the API route reproduces a source whose run depends on world
;; ===========================================================================
;;
;; The API route promotes `determinism/->artifact` of the source's compiled
;; plan. An artifact carries a program, the fx decisions and `:network`, but
;; not the source's decorator stubs, `:db-seed`, frame-setup or loaders. It
;; used to fold `[:world :setup]` into that program too, so neither spelling
;; reproduced a source with both `:setup` and world slots: without `:extends`
;; the promoted variant ran unseeded and fired the effect its source stubbed,
;; and with `:extends` of the source it ran the source's setup twice. The API
;; route now mirrors the Test-mode dialog: the program leaves setup out, and
;; promotion defaults `:extends` to the registered source, which supplies setup
;; and world exactly once.

#?(:clj
   (def ^:private world-real-calls
     "How many times the stubbed effect's REAL handler ran."
     (atom 0)))

#?(:clj
   (defn- reg-world-app!
     "The app under test for a world-dependent source. `:promo.world/load`
     issues `:promo.world/http`, whose real handler counts its calls and
     returns normally, so only the call count shows the stub was lost."
     []
     (rf/reg-fx :promo.world/http {:platforms #{:client :server}}
                (fn [_ctx _args] (swap! world-real-calls inc) nil))
     (rf/reg-event :promo.world/load (fn [_ _] {:fx [[:promo.world/http {}]]}))
     (rf/reg-event :promo.world/inc
       (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))))

#?(:clj
   (defn- api-recipe-promote!
     "Promote `source-id` by the documented API recipe (spec/017 §Promotion,
     the re-frame2 skill's story-mcp-loop §Promote a failing run), compiling
     its plan with the run inputs in `run-opts` when given and merging `opts`
     into the promotion opts."
     ([source-id promoted-id opts] (api-recipe-promote! source-id promoted-id opts nil))
     ([source-id promoted-id opts run-opts]
      (rf.story/promote-run-artifact!
        (rf.story.determinism/->artifact
          (rf.story/variant-plan source-id
                                 {:run-args (rf.story.args/run-arg-layers source-id run-opts)}))
        (merge {:variant/id promoted-id} opts)))))

#?(:clj
   (defn- world-verdict
     "Run variant `id` headless: its status, the final `:count`, and how many
     times the stubbed effect's real handler ran."
     [id]
     (reset! world-real-calls 0)
     (let [result (rf.story.async/deref-blocking (rf.story/run id) 10000)]
       {:status     (:status result)
        :count      (get-in result [:app-db :count])
        :real-calls @world-real-calls})))

#?(:clj
   (deftest api-route-reproduces-a-world-dependent-source
     (testing "a source whose run depends on a force-fx-stub decorator and a
               :db-seed, with and without a :setup, promotes by the documented
               API recipe into a variant that reproduces its count with 0 real
               calls, with no :extends given and with :extends of the source
               (rf2-hyheo)"
       (rf.story/install-canonical-vocabulary!)
       (reg-world-app!)
       (let [world {:decorators [[:rf.story/force-fx-stub :promo.world/http {:status 200}]]
                    :db-seed    {:count 10}
                    :script     [[:dispatch [:promo.world/load]]
                                 [:dispatch [:promo.world/inc]]]}]
         (doseq [[source-id body expected]
                 [[:story.promo-world/stubbed world 11]
                  [:story.promo-world/stubbed-setup
                   (assoc world :setup [[:dispatch [:promo.world/inc]]]) 12]]]
           (rf.story.registrar/reg-variant* source-id body)
           (let [source (world-verdict source-id)]
             (is (= {:status :pass :count expected :real-calls 0} source)
                 (str "control: " source-id " stubs its effect and runs seeded"))
             (doseq [[suffix opts] [["-api" nil]
                                    ["-api-extends" {:extends source-id}]]]
               (let [promoted (keyword (namespace source-id) (str (name source-id) suffix))]
                 (api-recipe-promote! source-id promoted opts)
                 (is (= source (world-verdict promoted))
                     (str promoted " reproduces " source-id
                          "'s count with 0 real calls"))))))))))

(deftest api-route-from-an-inline-plan-keeps-its-setup
  (testing "an inline plan names no registered variant, so there is nothing to
            extend: its setup stays folded into the promoted program and no
            :extends is defaulted (rf2-hyheo)"
    (let [art  (rf.story.determinism/->artifact
                 (rf.story.plan/variant-plan {:setup  [[:dispatch [:promo/seed 1]]]
                                              :script [[:dispatch [:promo/inc]]]}))
          body (rf.story.promotion/artifact->variant-body art)]
      (is (= [[:dispatch [:promo/seed 1]] [:dispatch [:promo/inc]]] (:script body)))
      (is (not (contains? body :extends)))))
  (testing "compiled with a run input, the folded setup already holds the value
            that ran, so the body carries no :args (rf2-30a8k)"
    (let [art  (rf.story.determinism/->artifact
                 (rf.story.plan/variant-plan
                   {:args   {:qty 1}
                    :setup  [[:dispatch [:promo/seed [:arg :qty]]]]
                    :script [[:dispatch [:promo/inc]]]}
                   {:run-args (rf.story.args/run-arg-layers nil {:cell-overrides {:qty 9}})}))
          body (rf.story.promotion/artifact->variant-body art)]
      (is (= [[:dispatch [:promo/seed 9]] [:dispatch [:promo/inc]]] (:script body)))
      (is (not (contains? body :extends)))
      (is (not (contains? body :args))))))

;; ===========================================================================
;; rf2-30a8k — the API route keeps the run inputs its source's setup reads
;; ===========================================================================
;;
;; The API route leaves a registered source's `[:world :setup]` out of the
;; promoted program, and `:extends` supplies it (rf2-hyheo). That setup
;; re-substitutes its `[:arg]` placeholders when the promoted variant compiles,
;; so a plan compiled with a cell override or an active mode used to promote
;; into a variant whose inherited setup read the source's default instead. The
;; artifact now records the args its plan resolved, and promotion carries the
;; ones the run inputs changed as the body's own `:args`, as it does for a
;; Test-mode capture (rf2-rky08).

(deftest api-route-carries-the-run-inputs-at-the-values-that-ran
  (testing "promoted by the API route, the body's :args carry exactly the keys
            the plan's run inputs changed, at the values the plan resolved, so
            the setup the body inherits through :extends is the setup the plan
            compiled (rf2-30a8k)"
    (rf.story.registrar/reg-story* :story.promo-input {:args {:tone "story"}})
    (rf.story.registrar/reg-mode* :Mode.promo-input/loud {:args {:qty 3 :tone "loud"}})
    (rf.story.registrar/reg-variant* :story.promo-input/seeded
      {:args   {:qty 1 :label "default"}
       :setup  [[:promo/seed [:arg :qty]]]
       :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]})
    (let [src     :story.promo-input/seeded
          plan-of (fn [run-opts]
                    (rf.story.plan/variant-plan
                      src {:run-args (rf.story.args/run-arg-layers src run-opts)}))
          body-of (fn [plan opts]
                    (rf.story.promotion/artifact->variant-body
                      (rf.story.determinism/->artifact plan) opts))
          ran     (plan-of {:active-modes [:Mode.promo-input/loud] :cell-overrides {:qty 9}})]
      (is (= [[:dispatch [:promo/seed 9]]] (get-in ran [:world :setup]))
          "precondition: the source plan compiled its setup with the override")
      (is (= {:qty 9 :tone "loud"} (:args (body-of ran nil)))
          "the cell override beats the variant's :qty, the mode beats the
           story's :tone, and :label, which no input changed, is not carried")
      (doseq [opts [nil {:extends src}]]
        (is (= (get-in ran [:world :setup])
               (get-in (rf.story.promotion/materialize-variant-plan
                         (rf.story.determinism/->artifact ran) opts)
                       [:world :setup]))
            "the promoted plan's inherited setup is the setup the source plan compiled"))
      (is (= {:qty 5 :tone "loud"} (:args (body-of ran {:args {:qty 5}})))
          "an explicit :args opt still wins over a carried input")
      (testing "default-input control: a plan compiled with no run inputs, with
                or without the ambient layers, carries no :args"
        (is (not (contains? (body-of (plan-of nil) nil) :args)))
        (is (not (contains? (body-of (rf.story.plan/variant-plan src) nil) :args)))))))

#?(:clj
   (defn- assert-api-input-promotion-fail-pass-fail
     "Run `source-body` under `source-id` with `run-opts`, whose input only the
     source's `:setup` reads, then promote it by the documented API recipe with
     those opts, with no `:extends` and with `:extends` of the source. Each
     promoted body must carry the 9 that ran, and, run with no opts, fail as
     its source did, pass once the app is fixed and fail when the fault
     returns, with its inherited setup running once per run."
     [source-id source-body run-opts]
     (rf.story/install-canonical-vocabulary!)
     (reg-seed! false)
     (rf.story.registrar/reg-variant* source-id source-body)
     (let [failing {:status :fail :assertions 1 :checks 0}]
       (is (= failing (verdict (rf.story.async/deref-blocking
                                 (rf.story/run source-id run-opts) 10000)))
           "the source fails under the run's input")
       (doseq [[suffix opts] [["-api" nil] ["-api-extends" {:extends source-id}]]]
         (let [promoted (keyword (namespace source-id) (str (name source-id) suffix))]
           (api-recipe-promote! source-id promoted opts run-opts)
           (is (= {:qty 9} (:args (rf.story.registrar/handler-meta :variant promoted)))
               (str promoted " carries the input that ran"))
           (is (= [failing {:status :pass :assertions 1 :checks 0} failing]
                  (fault-fix-fault promoted reg-seed!))
               (str promoted ", run with no opts, runs fail / pass / fail with its source's one assertion"))
           (reset! seeds 0)
           (is (= failing (run-verdict promoted)))
           (is (= 1 @seeds) (str promoted "'s inherited setup runs once per run")))))))

#?(:clj
   (deftest api-route-keeps-an-overridden-input-its-setup-reads
     (testing "the audit's shape: the source's :setup reads [:arg :qty], which
               defaults to 1, and its plan is compiled with :cell-overrides
               {:qty 9}. Promoted by the API recipe with no :extends and with
               :extends of the source, the variant inherits that setup and must
               run it with the 9, once (rf2-30a8k)"
       (assert-api-input-promotion-fail-pass-fail
         :story.promo-input/overridden
         {:tags   #{:test}
          :args   {:qty 1}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:cell-overrides {:qty 9}}))))

#?(:clj
   (deftest api-route-keeps-a-mode-supplied-input-its-setup-reads
     (testing "the source's :setup reads [:arg :qty], supplied only by an active
               mode, so the source does not compile without it. The promoted
               variant runs with no modes, so it must carry the 9 (rf2-30a8k)"
       (rf.story.registrar/reg-mode* :Mode.promo-input/qty-nine {:args {:qty 9}})
       (assert-api-input-promotion-fail-pass-fail
         :story.promo-input/mode
         {:tags   #{:test}
          :setup  [[:promo/seed [:arg :qty]]]
          :script [[:assert [:rf.assert/path-equals [:accepted?] true]]]}
         {:active-modes [:Mode.promo-input/qty-nine]}))))
