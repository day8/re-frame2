(ns re-frame.reply-test
  "Conformance for the EP-0011 uniform-reply-envelope substrate
  (`re-frame.reply`). Pins the three conformance groups EP-0011
  §Validation requires of the shared substrate slice:

    1. reply-map SCHEMA — exactly one valid `:status`; the per-status
       value/error conventions incl `:partial` (usable value AND
       structured error); the data-only invariant (NO host handles).
    2. functor LAWS for reply-target mapping — identity + composition,
       plus the naturality law `(complete (map-target f t) r) ==
       (f (complete t r))`, and the proof that mapping changes ONLY the
       completed event (not work id / status / cancellation / stale /
       tracing).
    3. STALE SUPPRESSION — the helper suppresses the app target (does NOT
       deliver) AND records the suppressed ledger/trace outcome carrying
       the carried + current correlation.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope. Pure substrate — no runtime fixture needed."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as reply]))

;; ---------------------------------------------------------------------------
;; Group 1 — reply-map schema.
;; ---------------------------------------------------------------------------

(deftest closed-status-vocabulary
  (testing "exactly the five statuses are valid"
    (is (= #{:ok :partial :error :cancelled :stale} reply/statuses)))
  (testing "a reply with no :status is invalid"
    (is (some #(= :rf.reply/missing-status (:rf.reply/problem %))
              (reply/validate-reply {:value 1}))))
  (testing "a reply with an out-of-vocabulary :status is invalid"
    (is (some #(= :rf.reply/invalid-status (:rf.reply/problem %))
              (reply/validate-reply {:status :done :value 1}))))
  (testing "exactly one valid :status passes"
    (is (reply/valid-reply? {:status :ok :value {:title "Welcome"}}))))

(deftest ok-conventions
  (testing ":ok requires :value and forbids :error"
    (is (reply/valid-reply? {:status :ok :value 42}))
    (is (some #(= :rf.reply/ok-missing-value (:rf.reply/problem %))
              (reply/validate-reply {:status :ok})))
    (is (some #(= :rf.reply/ok-has-error (:rf.reply/problem %))
              (reply/validate-reply {:status :ok :value 1 :error {:kind :x}})))))

(deftest error-conventions
  (testing ":error requires :error with a family :kind"
    (is (reply/valid-reply? {:status :error :error {:kind :rf.http/http-5xx}}))
    (is (some #(= :rf.reply/error-missing-error (:rf.reply/problem %))
              (reply/validate-reply {:status :error})))
    (is (some #(= :rf.reply/error-missing-kind (:rf.reply/problem %))
              (reply/validate-reply {:status :error :error {:no :kind}})))))

(deftest partial-conventions
  (testing ":partial carries BOTH usable :value AND structured :error with a family :kind"
    (is (reply/valid-reply?
          {:status :partial
           :value  {:user {:name "Ada"}}
           :error  {:kind :rf.graphql/partial-success
                    :errors [{:message "field x denied"}]}}))
    (is (some #(= :rf.reply/partial-missing-value (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :error {:kind :x}})))
    (is (some #(= :rf.reply/partial-missing-error (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :value 1})))
    (is (some #(= :rf.reply/error-missing-kind (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :value 1 :error {:no :kind}})))))

(deftest cancelled-conventions
  (testing ":cancelled requires :cancel/reason; :error MAY carry compatibility data"
    (is (reply/valid-reply? {:status :cancelled :cancel/reason :user}))
    (is (reply/valid-reply? {:status        :cancelled
                             :cancel/reason :actor-destroyed
                             :cancelled?    true
                             :error         {:kind :rf.http/aborted :reason :actor-destroyed}}))
    (is (some #(= :rf.reply/cancelled-missing-reason (:rf.reply/problem %))
              (reply/validate-reply {:status :cancelled})))))

(deftest stale-conventions
  (testing ":stale requires :stale? true + :stale/reason and carries NO :value"
    (is (reply/valid-reply? {:status :stale :stale? true :stale/reason :generation-mismatch}))
    (is (some #(= :rf.reply/stale-missing-flag (:rf.reply/problem %))
              (reply/validate-reply {:status :stale :stale/reason :x})))
    (is (some #(= :rf.reply/stale-missing-reason (:rf.reply/problem %))
              (reply/validate-reply {:status :stale :stale? true})))
    (is (some #(= :rf.reply/stale-has-value (:rf.reply/problem %))
              (reply/validate-reply {:status :stale :stale? true :stale/reason :x :value 1}))
        "a stale reply MUST NOT mutate app state — carrying :value would invite it")))

(deftest work-status-vocabulary
  (testing ":work/status, when present, is in the closed operational set"
    (is (reply/valid-reply? {:status :error :error {:kind :rf.http/timeout} :work/status :timed-out}))
    (is (some #(= :rf.reply/invalid-work-status (:rf.reply/problem %))
              (reply/validate-reply {:status :ok :value 1 :work/status :weird})))))

(deftest data-only-invariant-no-host-handles
  (testing "a fn anywhere in the reply is a host handle"
    (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
              (reply/validate-reply {:status :ok :value (fn [] 1)})))
    (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
              (reply/validate-reply {:status :ok :value {:a {:b (fn [] 1)}}}))
        "host handles are found at any depth")
    (let [probs (reply/validate-reply {:status :ok :value {:a {:cb (fn [] 1)}}})
          path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
      (is (= [:value :a :cb] path) "the problem reports the exact path to the handle")))
  (testing "a plain-data reply has no host-handle problem"
    (is (reply/valid-reply?
          {:status       :ok
           :value        {:title "Welcome"}
           :work/id      [:rf.work/http :article/by-id 42 1]
           :work/kind    :http
           :work/status  :completed
           :attempt      1
           :rf.frame/id  :app/main
           :started-at   1781078400123
           :completed-at 1781078400456
           :correlation  {:request-id [:article/by-id 42]}}))))

;; ---------------------------------------------------------------------------
;; Group 1b — target normalization.
;; ---------------------------------------------------------------------------

(deftest target-normalization
  (testing "the public short form normalizes to a :delivery :append descriptor"
    (is (= {:event [:article/load-replied {:id 42}] :delivery :append}
           (reply/normalize-target [:article/load-replied {:id 42}]))))
  (testing "the descriptor form defaults :delivery to :append"
    (is (= :append (:delivery (reply/normalize-target
                                {:event [:x] :suppress {:generation 1}})))))
  (testing "normalization is idempotent and preserves gate fields"
    (let [d (reply/normalize-target {:event [:x] :delivery :append
                                     :suppress {:route/nav-token "nav-7"}
                                     :dispatch-stale? true})]
      (is (= d (reply/normalize-target d)))))
  (testing "short-form projection round-trips a plain target"
    (is (= [:x 1] (reply/target->short-form [:x 1])))
    (is (= [:x 1] (reply/target->short-form {:event [:x 1] :delivery :append}))))
  (testing "short-form projection keeps the descriptor when gates are present"
    (is (map? (reply/target->short-form {:event [:x] :suppress {:g 1}}))))
  (testing "nil target ⇒ nil (no continuation)"
    (is (nil? (reply/normalize-target nil)))))

;; ---------------------------------------------------------------------------
;; Group 1c — completion appends the reply as the final arg.
;; ---------------------------------------------------------------------------

(deftest completion-appends-reply
  (let [reply {:status :ok :value {:title "Welcome"}
               :work/id [:rf.work/http :article/by-id 42 1]
               :completed-at 1781078400456}]
    (testing "the reply map is appended as the final event argument"
      (is (= [:article/load-replied {:id 42} reply]
             (reply/complete [:article/load-replied {:id 42}] reply))))
    (testing "completion works through the descriptor form too"
      (is (= [:article/load-replied {:id 42} reply]
             (reply/complete {:event [:article/load-replied {:id 42}] :delivery :append} reply))))
    (testing "nil target ⇒ nil (no delivery)"
      (is (nil? (reply/complete nil reply))))))

;; ---------------------------------------------------------------------------
;; Group 2 — the functor laws for reply-target mapping.
;; ---------------------------------------------------------------------------

(def ^:private target {:event [:article/replied {:id 42}] :delivery :append})

(def ^:private a-reply
  {:status  :ok
   :value   {:article {:id 42 :title "Welcome"}}
   :work/id [:rf.work/http :article/by-id 42 1]})

(defn- select-article-event
  "An event-transform: rewrite the appended reply's :value to its :article.
  Preserves the appended-reply event shape, so it composes in any order."
  [event]
  (let [r (peek event)]
    (conj (pop event) (update r :value :article))))

(defn- retarget-event-id
  "An event-transform: rename the event id (the head of the event vector),
  leaving the appended reply map untouched. Preserves the appended-reply
  event shape, so it composes with `select-article-event` in any order."
  [event]
  (assoc event 0 :parent/relay))

(defn- wrap-in-parent
  "An event-transform that relocates the continuation onto a parent event.
  Used for the naturality law (where it is applied once on both sides)."
  [event]
  [:parent/relay event])

(deftest functor-identity-law
  (testing "(map-target identity target) completes identically to target — identity law"
    (is (= (reply/complete target a-reply)
           (reply/complete (reply/map-target identity target) a-reply)))))

(deftest functor-naturality-law
  (testing "(complete (map-target f t) r) == (f (complete t r)) — the mapping law"
    (is (= (reply/complete (reply/map-target select-article-event target) a-reply)
           (select-article-event (reply/complete target a-reply))))
    (is (= (reply/complete (reply/map-target wrap-in-parent target) a-reply)
           (wrap-in-parent (reply/complete target a-reply))))))

(deftest functor-composition-law
  (testing "(map-target f (map-target g t)) == (map-target (comp f g) t) — composition law"
    ;; Both transforms preserve the appended-reply event shape, so they
    ;; compose in either order — exercising composition both ways.
    (let [f select-article-event
          g retarget-event-id]
      (is (= (reply/complete (reply/map-target f (reply/map-target g target)) a-reply)
             (reply/complete (reply/map-target (comp f g) target) a-reply)))
      (is (= (reply/complete (reply/map-target g (reply/map-target f target)) a-reply)
             (reply/complete (reply/map-target (comp g f) target) a-reply)))
      ;; And the composed result is the expected event: renamed id + selected value.
      (is (= [:parent/relay {:id 42} {:status :ok :value {:id 42 :title "Welcome"}
                                      :work/id [:rf.work/http :article/by-id 42 1]}]
             (reply/complete (reply/map-target (comp f g) target) a-reply))))))

(deftest mapping-changes-only-the-event
  (testing "mapping the target does NOT change the reply's work id / status / correlation"
    (let [mapped    (reply/map-target select-article-event target)
          completed (reply/complete mapped a-reply)
          delivered (peek completed)]
      ;; The COMPLETED EVENT changed (value→article); the reply's identity facts did not.
      (is (= [:rf.work/http :article/by-id 42 1] (:work/id delivered)))
      (is (= :ok (:status delivered)))
      ;; And issuance/correlation are unaffected — `map-target` stores no work-id /
      ;; status / suppression on the target (the functor law's structural guarantee):
      ;; the only difference between mapped and unmapped completion is the event payload.
      (is (= {:article {:id 42 :title "Welcome"}}
             (:value (peek (reply/complete target a-reply)))))
      (is (= {:id 42 :title "Welcome"}
             (:value (peek completed)))))))

;; ---------------------------------------------------------------------------
;; Group 3 — stale suppression: the correctness boundary.
;; ---------------------------------------------------------------------------

(deftest stale-gate-check
  (testing "matching correlation ⇒ not stale; superseded ⇒ stale"
    (is (false? (reply/stale? {:generation 4} {:generation 4})))
    (is (true?  (reply/stale? {:generation 4} {:generation 5})))
    (is (false? (reply/stale? {:work/id :w :generation 4}
                              {:work/id :w :generation 4 :extra :ignored}))
        "extra current keys are ignored — the carried gate's key set governs")
    (is (false? (reply/stale? nil nil)) "no gate ⇒ nothing to supersede")
    (is (true?  (reply/stale? {:generation 4} nil)) "current gone ⇒ stale")))

(deftest suppress-does-not-deliver-app-target
  (testing "suppression produces :status :stale, marks :suppressed, and does NOT deliver"
    (let [carried {:work/id [:rf.work/resource [:a/k] 4] :generation 4}
          current {:work/id [:rf.work/resource [:a/k] 5] :generation 5}
          {:keys [deliver? reply] :as out}
          (reply/suppress [:article/route-replied {:slug "welcome"}] carried current
                          {:work/id      (:work/id carried)
                           :work/kind    :resource
                           :rf.frame/id  :app/main
                           :stale/reason :resource/generation-mismatch})]
      (is (false? deliver?) "the app reply target MUST NOT run")
      (is (= :stale (:status reply)))
      (is (true? (:stale? reply)))
      (is (= :resource/generation-mismatch (:stale/reason reply)))
      (is (= :suppressed (:work/status reply)) "ledger terminal for a stale completion")
      (is (= :suppressed (:work/status out)))
      (is (not (contains? reply :value)) "a stale reply carries NO value — no app-state mutation"))))

(deftest suppress-records-carried-and-current-trace-facts
  (testing "the trace facts carry BOTH the carried and current correlation"
    (let [carried {:route/nav-token "nav-1"}
          current {:route/nav-token "nav-2"}
          {:keys [trace]} (reply/suppress [:x] carried current
                                          {:stale/reason :route/nav-token-mismatch})]
      (is (true? (:rf.reply/suppressed? trace)))
      (is (= :route/nav-token-mismatch (:stale/reason trace)))
      (is (= carried (:rf.reply/carried trace)))
      (is (= current (:rf.reply/current trace))))))

(deftest suppress-default-reason
  (testing "a default :stale/reason is supplied when the family does not name one"
    (is (= :rf.reply/correlation-mismatch
           (:stale/reason (:reply (reply/suppress [:x] {:g 1} {:g 2})))))))

(deftest suppress-dispatch-stale-opt-in
  (testing "framework test/tool targets MAY opt into stale delivery via :dispatch-stale?"
    (is (false? (:deliver? (reply/suppress {:event [:x]} {:g 1} {:g 2}))))
    (is (true?  (:deliver? (reply/suppress {:event [:x] :dispatch-stale? true} {:g 1} {:g 2})))
        "the explicit framework-only opt-in lets a test/tool target receive the stale envelope")))

;; ---------------------------------------------------------------------------
;; Group 4 — data-only trace summaries route through the shared elision walker.
;; ---------------------------------------------------------------------------

(deftest trace-summary-keeps-identity-facts-elides-wire-slots
  (testing "identity facts ride verbatim; wire slots route through elide-wire-value"
    ;; Frameless egress: opt into the identity walk so we exercise the wiring
    ;; deterministically (the carried-frame policy is tested in framed suites).
    (let [reply {:status       :ok
                 :value        {:title "Welcome"}
                 :error        nil
                 :work/id      [:rf.work/http :article/by-id 42 1]
                 :work/kind    :http
                 :rf.frame/id  :app/main
                 :completed-at 1781078400456
                 :correlation  {:request-id [:article/by-id 42]}}
          summary (reply/trace-summary reply {:rf.size/include-sensitive? true})]
      (is (= :ok (:status summary)))
      (is (= [:rf.work/http :article/by-id 42 1] (:work/id summary)))
      (is (= :http (:work/kind summary)))
      (is (= :app/main (:rf.frame/id summary)))
      (is (= 1781078400456 (:completed-at summary)))
      ;; With the identity opt-out the wire slots survive verbatim (no marks
      ;; declared), proving the slots were WALKED through elide-wire-value
      ;; rather than copied raw or dropped.
      (is (= {:title "Welcome"} (:value summary)))
      (is (= {:request-id [:article/by-id 42]} (:correlation summary)))))
  (testing "frameless egress with no opt-out FAILS CLOSED — wire slots redact"
    (let [summary (reply/trace-summary {:status :ok :value {:secret "x"}})]
      (is (= :ok (:status summary)) "identity facts still ride verbatim")
      (is (= :rf/redacted (:value summary))
          "the shared walker fails closed when no frame policy is reachable"))))
