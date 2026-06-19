(ns re-frame.story.promotion-test
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
  for `:extends` resolution where needed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.http.managed]       ;; production managed-HTTP fx surface (:rf.http/managed)
            [re-frame.http.test-support]  ;; stub install seam + canned-stub handlers
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story :as story]
            [re-frame.story.artifact :as artifact]
            [re-frame.story.determinism :as determinism]
            [re-frame.story.plan :as plan]
            [re-frame.story.promotion :as promotion]
            [re-frame.story.registrar :as registrar]))

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
  (registrar/clear-all!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (try (rf/init! plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (t))

(use-fixtures :each reset-side-table!)

;; ---- helpers ------------------------------------------------------------

(defn- sample-artifact
  "A run artifact with a two-step dispatch program + a stubbed fx
  decision + provenance slots + bulky captured evidence (so the
  provenance-trim assertions have something to drop)."
  []
  (artifact/make-run-artifact
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
          plan (promotion/materialize-variant-plan art)]
      (is (contains? plan :world))
      (is (contains? plan :script))
      (is (contains? plan :expect))
      (is (map? (:expect plan)))
      (is (= #{:client} (get-in plan [:world :platforms]))
          "the plan carries the compiler's normalized defaults")))

  (testing "the default policy projects the whole program into :script"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art)]
      (is (= [[:dispatch [:counter/init 5]]
              [:dispatch [:counter/inc]]]
             (:script plan)))
      (is (= [] (get-in plan [:world :setup]))
          "nothing is demoted to a silent precondition without a hint")))

  (testing "a :variant/id rides onto the materialized plan"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 (:variant/id plan))))))

;; ===========================================================================
;; §C1 bullet 4 — generated event program becomes script/setup per policy
;; ===========================================================================

(deftest program-projects-to-setup-and-script-per-policy
  (testing ":setup-count cuts preconditions off the front into [:world :setup]"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art {:setup-count 1})]
      (is (= [[:dispatch [:counter/init 5]]] (get-in plan [:world :setup]))
          "the first step is a precondition")
      (is (= [[:dispatch [:counter/inc]]] (:script plan))
          "the rest is behaviour-under-test")))

  (testing "an explicit :setup + :script partition is used verbatim"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:setup  [[:dispatch [:seed/a]]]
                      :script [[:dispatch [:act/b]]]})]
      (is (= [[:dispatch [:seed/a]]] (get-in plan [:world :setup])))
      (is (= [[:dispatch [:act/b]]] (:script plan)))))

  (testing "partition-program clamps an oversized :setup-count"
    (let [art (sample-artifact)
          {:keys [setup script]} (promotion/partition-program art {:setup-count 99})]
      (is (= 2 (count setup)) "every step becomes a precondition")
      (is (= [] script))))

  (testing "a bare event list in :script lifts to a tagged [:dispatch …] program"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan
                 art {:script [[:counter/reset]]})]
      (is (= [[:dispatch [:counter/reset]]] (:script plan))))))

;; ===========================================================================
;; §C1 bullet 2 — promotion preserves the source-artifact link
;; ===========================================================================

(deftest materialize-preserves-source-artifact-link
  (testing "the plan carries a :run-artifact back-link to the source"
    (let [art  (sample-artifact)
          plan (promotion/materialize-variant-plan art)
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
    (let [link (:run-artifact (promotion/materialize-variant-plan (sample-artifact)))]
      (is (not (contains? link :epoch-tape)))
      (is (not (contains? link :trace)))
      (is (not (contains? link :result))
          "a registered variant is a curation surface, not an evidence dump")))

  (testing "the promoted variant body also carries the source link"
    (let [body (promotion/artifact->variant-body (sample-artifact))]
      (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))))))

;; ===========================================================================
;; §C1 bullet 3 — promotion does NOT auto-register without the explicit call
;; ===========================================================================

(deftest materialize-registers-nothing
  (testing "materialize-variant-plan is pure — it registers NO variant"
    (let [art (sample-artifact)]
      (promotion/materialize-variant-plan art {:variant/id :story.counter/never})
      (is (not (registrar/registered? :variant :story.counter/never))
          "materialize must not touch the side-table")
      (is (empty? (registrar/registrations :variant))
          "the side-table stays empty after materialization"))))

(deftest promote-requires-explicit-variant-id
  (testing "promote-run-artifact! throws without an explicit :variant/id"
    (let [art (sample-artifact)]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"story-promote-no-id"
            (promotion/promote-run-artifact! art {})))
      (is (empty? (registrar/registrations :variant))
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
            (promotion/promote-run-artifact! art {:variant-id :story.counter/unqualified})))
      (is (empty? (registrar/registrations :variant))
          "the unqualified :variant-id registers nothing"))))

(deftest promote-registers-the-named-variant
  (testing "the explicit named call DOES register a curated variant"
    (let [art (sample-artifact)
          ret (promotion/promote-run-artifact!
                art {:variant/id :story.counter/regression-042})]
      (is (= :story.counter/regression-042 ret)
          "promote returns the registered variant id")
      (is (registrar/registered? :variant :story.counter/regression-042)
          "the variant is now in the side-table")))

  (testing "the registered body carries the source-artifact link + the program"
    (registrar/clear-all!)
    (let [art (sample-artifact)]
      (promotion/promote-run-artifact!
        art {:variant/id :story.counter/regression-042})
      (let [body (registrar/handler-meta :variant :story.counter/regression-042)]
        (is (= :rf.test/run-artifact (get-in body [:run-artifact :artifact/kind]))
            "provenance survives into the registered variant")
        ;; The registrar lowers the public `:script` bare step-vector to
        ;; the shipping `:play-script` slot (still a bare step vector).
        (is (= [[:dispatch [:counter/init 5]]
                [:dispatch [:counter/inc]]]
               (:play-script body))
            "the behaviour program is the registered play script"))))

  (testing "the facade re-exports route to the same bridge"
    (registrar/clear-all!)
    (let [art (sample-artifact)]
      (is (contains? (story/materialize-variant-plan art) :run-artifact))
      (is (= :story.counter/from-facade
             (story/promote-run-artifact!
               art {:variant/id :story.counter/from-facade})))
      (is (registrar/registered? :variant :story.counter/from-facade)))))

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
  rides back to this same origin event as `:rf/reply`, Spec 014 §Reply
  addressing). Mirrors the artifact_test helper so the round-trip exercises
  the same managed-HTTP fail-close path."
  [event-id [method url]]
  (rf/reg-event event-id
    (fn [{:keys [db]} [_ msg]]
      (if-let [reply (:rf/reply msg)]
        {:db (assoc db :got reply)}
        {:fx [[:rf.http/managed {:request {:method method :url url}
                                 :decode  :json}]]}))))

(defn- network-artifact
  "Compile a `:network` variant plan for `routes` and coerce it through the
  determinism gate's `->artifact` (the real materialize-to-artifact seam),
  so the artifact carries both the `:network` route map and the
  `:fx-decisions` managed-stub redirect — exactly what a recorded HTTP run
  produces."
  [routes script]
  (let [variant-id :story.promo-net/v
        plan       (plan/variant-plan
                     variant-id
                     {:lookup {variant-id {:network routes
                                           :script  script}}})]
    (determinism/->artifact plan)))

(deftest promotion-link-of-network-run-re-derives-it
  (testing "a variant promoted from a :network-stubbed run carries a
            provenance link that ROUND-TRIPS through replay-run-artifact to
            the SAME run (rf2-87duu — :network is load-bearing for replay)"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          ;; the trimmed provenance link a promotion stores on :run-artifact
          link   (promotion/provenance-link art)]

      ;; The link must itself carry the network route map — it is the slot
      ;; replay re-installs the per-route stubs from. (RED without the fix.)
      (is (= routes (:network link))
          "the provenance link preserves :network so replay can re-install
           the route stubs (rf2-87duu — same class as rf2-tymyh)")

      ;; A direct replay of the SOURCE artifact: the route matches and the
      ;; recorded :ok reply is synthesised. This is the run that was promoted.
      (let [src (artifact/replay-run-artifact art)]
        (is (= :pass (:status src)))
        (is (= :success (:kind (:got (:app-db src))))
            "the source run matched the route stub"))

      ;; Re-deriving from the LINK alone must reproduce the SAME run — the
      ;; provenance link is replayable on its own (it carries :artifact/kind,
      ;; :event-program, :fx-decisions, and — post-fix — :network). Without
      ;; :network the managed request fail-closes on "no stub matched"
      ;; (:rf.http/transport), a DIFFERENT run.
      (let [from-link (artifact/replay-run-artifact link)
            got       (:got (:app-db from-link))]
        (is (= :pass (:status from-link))
            "the link re-derives a passing run — NOT a fail-closed one")
        (is (= :success (:kind got))
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
          body   (promotion/artifact->variant-body art)]
      (is (= routes (:network body))
          "the per-route reply map rides the body's runnable :network slot")
      ;; The artifact's :fx-decisions carries the lowered managed-stub redirect;
      ;; the body's :network slot OWNS :rf.http/managed (it re-derives the same
      ;; redirect through plan/lower-network), so the lifted :fx-overrides must
      ;; NOT also set it — else check-network-fx-conflict! hard-fails.
      (is (not (contains? (:fx-overrides body) plan/managed-fx-id))
          ":rf.http/managed is dropped from :fx-overrides — :network owns it"))))

(deftest promoted-variant-body-compiles-to-installed-network
  (testing "compiling the promoted body keeps the routes at [:world :network]
            (so the run installs the stubs) + lowers :rf.http/managed to the
            managed-stub fx — NOT an empty network that fail-closes (rf2-vf8es)"
    (register-network-event! :promo-net/get-cart [:get "/api/cart"])
    (let [routes {[:get "/api/cart"] {:reply {:ok {:items [{:sku "A"}]}}}}
          art    (network-artifact routes [[:dispatch [:promo-net/get-cart]]])
          body   (promotion/artifact->variant-body art)
          ;; compile the body as an inline plan target (read-only, no register)
          plan   (plan/variant-plan body)]
      (is (= routes (get-in plan [:world :network]))
          "the compiled plan keeps the route map at [:world :network]")
      (is (= plan/managed-stub-fx-id
             (get-in plan [:world :frame :fx-overrides plan/managed-fx-id]))
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
          src    (artifact/replay-run-artifact art)
          ;; the PROMOTED VARIANT: body → compiled plan → run-artifact. Running
          ;; the variant = compiling its body + executing it; ->artifact is the
          ;; real materialize-to-run seam, and replay re-installs the body's
          ;; :network route stubs (with-network-stubs!).
          body   (promotion/artifact->variant-body art)
          plan   (plan/variant-plan body)
          var-art (determinism/->artifact plan)
          ran    (artifact/replay-run-artifact var-art)]
      (is (= routes (:network var-art))
          "the promoted variant's run-artifact carries the route map (NOT empty)")
      (is (= (:status src) (:status ran) :pass)
          "the promoted variant runs to the SAME status as the source run")
      (is (= (:got (:app-db src)) (:got (:app-db ran)))
          "the promoted variant reproduces the SAME recorded reply")
      (is (= :success (:kind (:got (:app-db ran))))
          "the route stub matched on the promoted-variant run — NOT a
           fail-closed 'no stub matched' transport failure"))))
