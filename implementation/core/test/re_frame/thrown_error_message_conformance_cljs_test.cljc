(ns re-frame.thrown-error-message-conformance-cljs-test
  "rf2-vvixub — the thrown-error HUMAN-MESSAGE conformance gate.

  Spec 009 §The thrown-error shape rules the human-message contract:

    1. `(ex-message e)` is a human-actionable one-line sentence — NOT
       the bare stringified `:rf.error/…` discriminator keyword.
    2. `:rf.error/id` (ex-data) is the SOLE canonical machine
       discriminator; tools/tests branch on it, never on the message.
    3. The message is stable in MEANING, not bytes — tests MUST NOT
       exact-equal it.
    4. The message carries a trailing `[:rf.error/<id>]` token for
       log/CI greppability.

  This gate makes rule 1+4 REAL, not documentary: it rejects any
  framework throw whose message regresses to a keyword-only shape, and
  asserts the central builder + every CENTRAL per-surface site routed
  through it (rf2-vvixub PR) emits the canonical message.

  CORPUS BACKSTOP (rf2-6bb3pg): this suite exercises the BUILDER
  PREDICATES + a curated set of central sites in-process, so a NEW or
  never-converted `(ex-info \":rf.error/…\")` site elsewhere is invisible
  to it. The whole-tree anti-regression sweep is now
  `scripts/check_thrown_error_messages.py` — a source scan (run by
  `scripts/test-fast-pr.sh`) that fails on ANY framework `(ex-info …)`
  whose message position is a bare `:rf.*` discriminator keyword. That
  corpus gate, not this allow-list, is what guarantees the rollout can't
  silently regress; this suite proves the BUILDER and predicates the
  corpus gate's contract rests on are themselves correct.

  Dual-runtime: the ns ends in `-cljs-test` so it rides the always-on
  `:node-test` gate (`npm run test:cljs`); cognitect-test-runner also
  discovers the `.cljc` on the JVM (`clojure -M:test`). Pure data — no
  adapter / runtime state required (the central sites are exercised
  directly), so no reset-runtime fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.app-value :as app-value]
            [re-frame.realm :as realm]
            [re-frame.flows.registry :as flows-registry]
            [re-frame.flows.topo :as flows-topo]
            [re-frame.routing.registry :as routing-registry]))

;; ============================================================================
;; The builder predicates — the conformance machinery itself
;; ============================================================================

(deftest keyword-only-message?-rejects-bare-keyword-strings
  (testing "a bare stringified :rf.error/… keyword is flagged (the OLD shape)"
    (is (true? (error/keyword-only-message? ":rf.error/no-adapter-installed")))
    (is (true? (error/keyword-only-message? ":rf.error/flow-bad-id")))
    (is (true? (error/keyword-only-message? ":rf.error/route-too-many-keys"))))
  (testing "a human sentence carrying the token is NOT flagged (the NEW shape)"
    (is (false? (error/keyword-only-message?
                  "rf/init! cannot continue because no adapter is installed; require an adapter ns and install it before boot. [:rf.error/no-adapter-installed]")))
    (is (false? (error/keyword-only-message?
                  "some other text :rf.error/x in the middle"))))
  (testing "nil / non-string degrade to false (never a true positive)"
    (is (false? (error/keyword-only-message? nil)))
    (is (false? (error/keyword-only-message? :rf.error/x)))))

(deftest message-has-id-token?-detects-the-trailing-token
  (testing "the bracketed [:rf.error/<id>] token is detected anywhere"
    (is (true? (error/message-has-id-token?
                 "human sentence here. [:rf.error/no-adapter-installed]")))
    (is (true? (error/message-has-id-token? "[:rf.error/flow-bad-id]"))))
  (testing "a message with NO token is rejected (rule 4 floor)"
    (is (false? (error/message-has-id-token? "just a human sentence")))
    (is (false? (error/message-has-id-token? ":rf.error/no-adapter-installed")))
    (is (false? (error/message-has-id-token? nil)))))

;; ============================================================================
;; The central builder — derives a conformant message from reason + id
;; ============================================================================

(deftest thrown-ex-info-derives-the-canonical-shape
  (let [e (error/thrown-ex-info
            :rf.error/no-adapter-installed
            'rf/init!
            "rf/init! cannot continue because no adapter is installed; require an adapter ns and install it before boot.")
        msg (ex-message e)
        data (ex-data e)]
    (testing "ex-data carries the canonical discriminator slot"
      (is (= :rf.error/no-adapter-installed (:rf.error/id data))))
    (testing ":where + :recovery default + :reason populate"
      (is (= 'rf/init! (:where data)))
      (is (= :no-recovery (:recovery data)))
      (is (= "rf/init! cannot continue because no adapter is installed; require an adapter ns and install it before boot."
             (:reason data))))
    (testing "the message LEADS with the human sentence (rule 1)"
      (is (not (error/keyword-only-message? msg))
          "message must not be a bare keyword")
      (is (re-find #"^rf/init! cannot continue" msg)
          "message starts with the human sentence, not the keyword"))
    (testing "the message TRAILS with the [:rf.error/<id>] token (rule 4)"
      (is (error/message-has-id-token? msg))
      (is (re-find #"\[:rf\.error/no-adapter-installed\]$" msg)))))

(deftest thrown-ex-info-honours-recovery-and-extra-slots
  (let [e (error/thrown-ex-info
            :rf.error/flow-bad-id
            'rf/reg-flow
            ":id must be a keyword"
            {:recovery :fix-registration
             :extra    {:flow {:id "not-a-kw"} :bad-key :id}})
        data (ex-data e)]
    (is (= :fix-registration (:recovery data)) "explicit :recovery overrides the default")
    (is (= {:id "not-a-kw"} (:flow data)) ":extra slots merge on top")
    (is (= :id (:bad-key data)))
    (is (= ":id must be a keyword" (:reason data)))
    (is (error/message-has-id-token? (ex-message e)))))

(deftest throw-error!-builds-and-throws
  (let [thrown (try
                 (error/throw-error! :rf.error/no-adapter-specified
                                     'rf/init!
                                     "rf/init! requires an adapter spec map")
                 ::no-throw
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e e))]
    (is (not= ::no-throw thrown) "throw-error! actually throws")
    (is (= :rf.error/no-adapter-specified (:rf.error/id (ex-data thrown))))
    (is (not (error/keyword-only-message? (ex-message thrown))))
    (is (error/message-has-id-token? (ex-message thrown)))))

;; ============================================================================
;; The CENTRAL per-surface sites converted in the rf2-vvixub PR all emit
;; the conformant shape (the "no keyword-only message regression" gate).
;; Each site is exercised through its public throw path and its message is
;; asserted to be a human sentence carrying the token, with the canonical
;; discriminator in :rf.error/id. (The 10 surface CHILDREN are converted by
;; their own beads; this gate covers the contract + the central conversions.)
;; ============================================================================

(defn- ex-info-class? [e]
  #?(:clj (instance? clojure.lang.ExceptionInfo e)
     :cljs (instance? ExceptionInfo e)))

(defn- assert-conformant-throw!
  "Assert `thunk` throws an ex-info whose message is conformant (human
  sentence + token, never a bare keyword) and whose `:rf.error/id` equals
  `expected-id`."
  [label expected-id thunk]
  (let [thrown (try (thunk) ::no-throw
                    (catch #?(:clj Throwable :cljs :default) e e))]
    (testing (str label " throws a conformant ex-info")
      (is (ex-info-class? thrown) (str label " threw an ex-info"))
      (when (ex-info-class? thrown)
        (let [msg (ex-message thrown)]
          (is (= expected-id (:rf.error/id (ex-data thrown)))
              (str label " carries the canonical :rf.error/id discriminator"))
          (is (not (error/keyword-only-message? msg))
              (str label " message is NOT a bare keyword (rule 1)"))
          (is (error/message-has-id-token? msg)
              (str label " message carries the [:rf.error/<id>] token (rule 4)")))))))

(deftest require-fn!-emits-conformant-missing-artefact-throw
  ;; late-bind/require-fn! — the missing-artefact exemplar. An unregistered
  ;; hook key (no producer published it) throws the *-artefact-missing shape.
  (assert-conformant-throw!
    "require-fn! (missing artefact)"
    :rf.error/flows-artefact-missing
    #(late-bind/require-fn!
       ::vvixub-definitely-unregistered-hook
       'rf/reg-flow
       {:error-keyword :rf.error/flows-artefact-missing
        :maven         "com.day8.re-frame/re-frame2-flows"
        :require-ns     're-frame.flows})))

(deftest flow-error-emits-conformant-validation-throw
  ;; flows.registry validation — the :error→:rf.error/id exemplar. An
  ;; invalid flow (non-keyword :id) trips the validation cascade. flow-error
  ;; is private; reach it through the public reg-flow validation entry point.
  (assert-conformant-throw!
    "reg-flow (bad :id)"
    :rf.error/flow-bad-id
    #(#'flows-registry/validate-flow
       {:id     "not-a-keyword"
        :inputs [[:a]]
        :output (fn [_] nil)
        :path   [:out]})))

(deftest route-error-emits-conformant-routing-throw
  ;; routing.registry route-error — the routing exemplar. Build directly
  ;; (route-error is the canonical helper every routing throw routes through).
  (let [e (routing-registry/route-error
            :rf.error/route-too-many-keys
            'rf/match-url
            "the decoded URL declared more keys than the interning cap allows"
            {:limit 64 :count 99})]
    (is (= :rf.error/route-too-many-keys (:rf.error/id (ex-data e))))
    (is (= 64 (:limit (ex-data e))) "per-site :extras slots merge on top")
    (is (not (error/keyword-only-message? (ex-message e)))
        "route-error message is NOT a bare keyword (rule 1)")
    (is (error/message-has-id-token? (ex-message e))
        "route-error message carries the [:rf.error/<id>] token (rule 4)")))

;; ============================================================================
;; flows/topo throws (rf2-1jh98u) — the THREE topo categories that previously
;; threw a bare-keyword `(ex-info ":rf.error/…" …)` directly, bypassing the
;; flows.registry/flow-error helper. They now route through the central builder
;; (`re-frame.error/throw-error!`), so the message LEADS with the human
;; sentence + TRAILS with the token, and `:rf.error/id` is the discriminator.
;; ============================================================================

(deftest flow-path-overlap-emits-conformant-throw
  ;; detect-output-path-overlap! — two flows whose output :paths collide.
  (assert-conformant-throw!
    "detect-output-path-overlap! (overlapping :paths)"
    :rf.error/flow-path-overlap
    #(flows-topo/detect-output-path-overlap!
       {:a {:id :a :inputs [[:w]] :output identity :path [:x]}
        :b {:id :b :inputs [[:h]] :output identity :path [:x]}})))

(deftest flow-cycle-emits-conformant-throw
  ;; topo-sort — two flows forming a dependency cycle.
  (assert-conformant-throw!
    "topo-sort (cyclic flow dependency)"
    :rf.error/flow-cycle
    #(flows-topo/topo-sort
       {:a {:id :a :inputs [[:b]] :output identity :path [:a]}
        :b {:id :b :inputs [[:a]] :output identity :path [:b]}})))

(deftest flow-cycle-extract-invariant-emits-conformant-throw
  ;; extract-cycle-path — the internal dead-end invariant (a stuck node with no
  ;; stuck dependency to follow). Reach the private helper directly.
  (assert-conformant-throw!
    "extract-cycle-path (internal dead-end invariant)"
    :rf.error/flow-cycle-extract-invariant
    #(#'flows-topo/extract-cycle-path {:a #{}} #{:a})))

;; ============================================================================
;; app-value / realm throws (rf2-p7kwpt) — the app-as-value and realm surface
;; exemplars. Both previously threw with the legacy `:error/id` discriminator;
;; they now route through the central builder so the message is a human sentence
;; naming the public concept (rf/app / rf/realm) carrying the [:rf.error/<id>]
;; token, with the canonical discriminator in `:rf.error/id`.
;; ============================================================================

(deftest app-value-throw-emits-conformant-shape
  ;; rf2-p7kwpt — the app-as-value surface exemplar. A bad rf/app input (a
  ;; non-module :modules entry) routes through the central builder, so the
  ;; message is a human sentence naming the public concept (rf/app) carrying
  ;; the [:rf.error/<id>] token, with the canonical discriminator in
  ;; :rf.error/id (NOT the legacy :error/id slot).
  (assert-conformant-throw!
    "rf/app (non-module :modules entry)"
    :rf.error/invalid-app
    #(app-value/app {:id :conf/app :modules [{:not :a-module}]})))

(deftest realm-throw-emits-conformant-shape
  ;; rf2-p7kwpt — the realm surface exemplar. A bad rf/realm input (a missing
  ;; :id) routes through the central builder: the message names rf/realm and
  ;; carries the token, with :rf.error/id as the SOLE discriminator.
  (assert-conformant-throw!
    "rf/realm (missing :id)"
    :rf.error/invalid-realm
    #(realm/construct-realm {})))
