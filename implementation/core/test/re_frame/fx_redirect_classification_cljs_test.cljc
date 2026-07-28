(ns re-frame.fx-redirect-classification-cljs-test
  "rf2-2siusz — keyword-redirect run-modes: the resolved redirect TARGET's own
  `[:rf.fx/id :rf.fx/args]` trace slots (`:rf.fx/handled`, the always-on fx
  error traces, `:rf.fx/skipped-on-platform`) carried the RAW args of the
  ORIGINAL fx. The rf2-32ffq1 (PR #5694) `project-fx-args` chokepoint keys off
  the trace's `:rf.fx/id` — under an `:fx-overrides` id-redirect that slot
  carries the STUB id, whose registration declares nothing and matches no
  dynamic case, so a `:sensitive? true` `:rf.http/managed` request redirected
  to a canned/test stub rode raw at the stub's handled trace (and ANY
  classified fx redirected to an unclassified stub leaked its static-declared
  paths the same way).

  THE FIX: `handle-one-fx` stamps the ORIGINAL fx-id as `:rf.fx/from` on the
  fx-arg-bearing emits whenever a keyword redirect resolved (the tag
  vocabulary `:rf.fx/override-applied` already uses), and `project-fx-tags`
  composes `project-fx-args` for BOTH ids — the redirect target's own
  declaration first, then the ORIGINAL registration's static paths + per-fx-id
  dynamic classification.

  Deterministic projector teeth drive `project-trace-event` on hand-built
  trace shapes (mirroring fx_aggregate_classification_cljs_test); the live
  round-trips prove the redirect target's BODY still receives the RAW args
  (classification is egress-only) while every emitted slot redacts.

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both run it
  (http rides core's test-only classpath).

  ## Posture split (rf2-d2841)

  Section A drives `project-trace-event` on hand-built shapes and needs no
  trace stream, so all of it — the composition of the original id's static
  paths with the target's, the dynamic `:sensitive?` case, and the fail-open
  precision rows — runs under `scripts/test-core-prod-gate.sh` unchanged.

  Section B's live round-trips read the DEV TRACE stream. Their trace-reading
  steps sit verbatim inside `(when interop/debug-enabled? …)` arms, sweeps
  included: `(is (not (some #(leaks? pw-sentinel %) @traces)))` over an EMPTY
  `@traces` passes for free, and a redirect-redaction suite that certifies
  itself green having emitted nothing is worse than no suite.

  Each round-trip's step 1 stays OUTSIDE the arm, and it is the strongest
  production claim this file makes: the KEYWORD REDIRECT ITSELF still happens
  and the target still receives the args RAW. `::stub` capturing the raw token,
  and the canned success reply reaching `:on-success`, prove the redirect
  resolved and the cascade completed — in the posture that ships, where the
  provenance tag that names the redirect is invisible."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.classification :as classification]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Boot the optional http artefact so the
            ;; `:http/project-managed-fx-args` hook is bound…
            [re-frame.http.managed]
            ;; …and the test-support sibling so the canned-stub fxs
            ;; (`:rf.http/managed-canned-*`) are registered for the live
            ;; keyword-redirect round-trip (the exact rf2-2siusz scenario).
            [re-frame.http.test-support]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; A UNIQUE sentinel that must never appear raw in any projected trace slot.
(def ^:private pw-sentinel "rf2-2siusz-PW-4c8d19")

(defn- leaks? [sentinel x] (str/includes? (pr-str x) sentinel))

(defn- project [ev] (:tags (classification/project-trace-event ev)))

(defn- record-traces! []
  (let [a (atom [])]
    (rf/register-listener! :trace ::probe (fn [ev] (swap! a conj ev)))
    a))

;; =====================================================================
;; A. Deterministic projector teeth — hand-built trace shapes.
;; =====================================================================

(deftest redirected-slot-composes-original-static-classification
  (testing "a redirected [:rf.fx/id :rf.fx/args] slot carrying :rf.fx/from
            applies the ORIGINAL registration's static :sensitive on top of
            the (unclassified) redirect target — on every op that stamps the
            slot shape"
    (registrar/register! :fx ::orig {:sensitive [[:token]]})
    (doseq [op [:rf.fx/handled :rf.error/fx-handler-exception
                :rf.fx/skipped-on-platform]]
      (let [t (project {:operation op
                        :tags {:frame       :rf/default
                               :rf.fx/id    ::stub
                               :rf.fx/from  ::orig
                               :rf.fx/args  {:token pw-sentinel :note "plain"}}})]
        (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :token]))
            (str op " redacts the original id's declared :token path"))
        (is (= "plain" (get-in t [:rf.fx/args :note]))
            (str op " keeps the non-secret sibling (path-precise)"))
        (is (= ::stub (:rf.fx/id t)) "the resolved stub id survives")
        (is (= ::orig (:rf.fx/from t)) "the provenance tag survives")
        (is (not (leaks? pw-sentinel t)) (str op " leaks no secret"))))))

(deftest redirected-slot-composes-both-registrations
  (testing "the redirect TARGET's own static declaration composes WITH the
            original's — both ids' paths redact on the same slot"
    (registrar/register! :fx ::orig {:sensitive [[:token]]})
    (registrar/register! :fx ::stub {:sensitive [[:note]]})
    (let [t (project {:operation :rf.fx/handled
                      :tags {:frame       :rf/default
                             :rf.fx/id    ::stub
                             :rf.fx/from  ::orig
                             :rf.fx/args  {:token pw-sentinel
                                           :note  pw-sentinel
                                           :other "x"}}})]
      (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :token]))
          "the ORIGINAL id's path redacts")
      (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :note]))
          "the redirect TARGET's own path redacts too")
      (is (= "x" (get-in t [:rf.fx/args :other])) "unclassified slots survive")
      (is (not (leaks? pw-sentinel t))))))

(deftest redirected-managed-honours-dynamic-sensitive-flag
  (testing "the rf2-2siusz scenario shape: a :sensitive? true managed args map
            on a redirected stub's handled slot redacts through the ORIGINAL
            :rf.http/managed id's DYNAMIC classification (the stub id — an
            unregistered :rf.test/* id here — matches no dynamic case, so the
            :rf.fx/from :rf.http/managed provenance is what classifies)"
    (let [t (project {:operation :rf.fx/handled
                      :tags {:frame      :rf/default
                             :rf.fx/id   :rf.test/managed-http-stub-1
                             :rf.fx/from :rf.http/managed
                             :rf.fx/args {:request    {:method :post
                                                       :url    "https://api.example.test/login"
                                                       :body   {:password pw-sentinel}}
                                          :sensitive? true
                                          :decode     :json}}})]
      (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :request :body]))
          "the sensitive request's whole :body reads :rf/redacted")
      (is (= "https://api.example.test/login"
             (get-in t [:rf.fx/args :request :url]))
          "shape retained — the (query-free) url survives")
      (is (not (leaks? pw-sentinel t))))))

(deftest unredirected-slot-stays-fail-open
  (testing "precision: WITHOUT :rf.fx/from an unclassified id's args ride raw
            (the documented unregistered-fx fail-open — no reflexive
            over-redaction), and :rf.fx/from equal to :rf.fx/id composes
            nothing extra"
    (let [t (project {:operation :rf.fx/handled
                      :tags {:frame      :rf/default
                             :rf.fx/id   ::stub
                             :rf.fx/args {:token pw-sentinel}}})]
      (is (= pw-sentinel (get-in t [:rf.fx/args :token]))
          "no provenance, no registration → args ride raw"))
    (registrar/register! :fx ::orig {:sensitive [[:token]]})
    (let [t (project {:operation :rf.fx/handled
                      :tags {:frame      :rf/default
                             :rf.fx/id   ::orig
                             :rf.fx/from ::orig
                             :rf.fx/args {:token pw-sentinel :note "n"}}})]
      (is (= privacy/redacted-sentinel (get-in t [:rf.fx/args :token]))
          "a from == id stamp projects once, correctly")
      (is (= "n" (get-in t [:rf.fx/args :note]))))))

;; =====================================================================
;; B. Live round-trips — dispatch through the real drain with an
;;    :fx-overrides keyword redirect; the stub body reads RAW args while
;;    every emitted trace slot redacts.
;; =====================================================================

(deftest live-redirect-stamps-from-and-redacts-original-static-paths
  (testing "a classified fx keyword-redirected to an unclassified stub: the
            stub body receives the RAW args; the stub's :rf.fx/handled stamps
            :rf.fx/from and redacts the ORIGINAL id's declared path"
    (let [got (atom ::none)]
      (rf/reg-fx ::orig {:sensitive [[:token]]} (fn [_ _] nil))
      (rf/reg-fx ::stub (fn [_ args] (reset! got args)))
      (rf/reg-event ::go
        (fn [_ _] {:fx [[::orig {:token pw-sentinel :note "plain"}]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::go] {:fx-overrides {::orig ::stub}})
        (rf/unregister-listener! :trace ::probe)

        ;; 1. control flow untouched — the stub received the raw token.
        ;;    ALWAYS-ON (rf2-d2841): this row alone proves the keyword redirect
        ;;    RESOLVED (`::stub` ran, not `::orig`) and that redaction is
        ;;    egress-only. It is the production half of the whole scenario.
        (is (= pw-sentinel (:token @got))
            "the redirect target received the RAW token (egress-only redaction)")
        (is (= "plain" (:note @got))
            "and the non-secret sibling too — the body sees the args verbatim")

       ;; rf2-d2841 — steps 2-4 read the dev trace stream; step 4's sweep would
       ;; certify "no leak" over an empty `@traces`. Kept verbatim in the arm.
       (when interop/debug-enabled?
        ;; 2. the stub's own handled slot stamps provenance + redacts.
        (let [handled (filter #(= ::stub (get-in % [:tags :rf.fx/id])) @traces)]
          (is (seq handled) "the stub emitted a :rf.fx/handled trace")
          (doseq [ev handled]
            (is (= ::orig (get-in ev [:tags :rf.fx/from]))
                "the handled trace carries the ORIGINAL id as :rf.fx/from")
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args :token]))
                "the original id's declared :token path redacts on the stub slot")
            (is (= "plain" (get-in ev [:tags :rf.fx/args :note]))
                "the non-secret sibling survives")))

        ;; 3. the :rf.event/fx aggregate (stamped under the ORIGINAL id)
        ;;    redacts as before — the rf2-6h3c02 pin.
        (let [entries (for [ev    @traces
                            :let  [fx-vec (get-in ev [:tags :rf.event/fx])]
                            :when (vector? fx-vec)
                            [id args] fx-vec
                            :when (= ::orig id)]
                        args)]
          (is (seq entries) "the do-fx aggregate carried the original entry")
          (doseq [args entries]
            (is (= privacy/redacted-sentinel (:token args)))))

        ;; 4. whole-stream sweep — the token appears NOWHERE.
        (is (not (some #(leaks? pw-sentinel %) @traces))
            "no emitted trace event leaks the token sentinel"))))))

(deftest live-canned-stub-redirect-redacts-sensitive-managed-request
  (testing "rf2-2siusz acceptance: a :sensitive? true :rf.http/managed request
            keyword-redirected to the framework canned-success stub — the
            canned reply still lands (control), and the stub's own
            :rf.fx/handled slot redacts the request body through the ORIGINAL
            id's dynamic classification"
    (let [reply (atom ::none)]
      (rf/reg-event ::got (fn [{:keys [db]} [_ r]] (reset! reply r) {:db db}))
      (rf/reg-event ::issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:method :post
                               :url    "https://api.example.test/login"
                               :body   {:password pw-sentinel}
                               :request-content-type :json
                               :sensitive? true}
                  :decode     :json
                  :value      {:ok true}
                  :on-success [::got]
                  :on-failure [::got]}]]}))
      (let [traces (record-traces!)]
        (rf/dispatch-sync [::issue]
                          {:fx-overrides {:rf.http/managed
                                          :rf.http/managed-canned-success}})
        (rf/unregister-listener! :trace ::probe)

        ;; 1. control — the canned stub actually ran and replied.
        ;;    ALWAYS-ON (rf2-d2841): the redirect resolved to the framework
        ;;    canned stub, the stub ran, and its reply completed the cascade —
        ;;    all of it true in the posture that ships.
        (is (= {:status :ok :value {:ok true}} @reply)
            "the canned success reply reached the :on-success target")

       ;; rf2-d2841 — steps 2-4 read the dev trace stream; step 4's sweep would
       ;; certify "no leak" over an empty `@traces`. Kept verbatim in the arm.
       (when interop/debug-enabled?
        ;; 2. the stub's own handled slot: provenance + dynamic redaction.
        (let [handled (filter #(= :rf.http/managed-canned-success
                                  (get-in % [:tags :rf.fx/id]))
                              @traces)]
          (is (seq handled) "the canned stub emitted its own :rf.fx/handled")
          (doseq [ev handled]
            (is (= :rf.http/managed (get-in ev [:tags :rf.fx/from]))
                "the stub's handled trace names the ORIGINAL managed id")
            (is (= privacy/redacted-sentinel
                   (get-in ev [:tags :rf.fx/args :request :body]))
                "the sensitive request's whole :body redacts on the stub slot")
            (is (= "https://api.example.test/login"
                   (get-in ev [:tags :rf.fx/args :request :url]))
                "shape retained — the url survives")))

        ;; 3. the redirect fact also rode the dedicated override trace.
        (is (some #(and (= :rf.fx/override-applied (:operation %))
                        (= :rf.http/managed (get-in % [:tags :rf.fx/from]))
                        (= :rf.http/managed-canned-success
                           (get-in % [:tags :rf.fx/to])))
                  @traces)
            ":rf.fx/override-applied still records the redirect pair")

        ;; 4. whole-stream sweep — the password appears NOWHERE.
        (is (not (some #(leaks? pw-sentinel %) @traces))
            "no emitted trace event leaks the password sentinel"))))))
