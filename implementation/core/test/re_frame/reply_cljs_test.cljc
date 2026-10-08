(ns re-frame.reply-cljs-test
  "The uniform reply-envelope substrate (`re-frame.reply`): the reply-map
  schema and its data-only invariant, reply-target normalization and
  completion, the reply-mapping functor laws, stale suppression, trace
  summaries, and the reply thrown-error shape.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply envelope.
  Pure substrate — no runtime fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.reply :as rf.reply]))

(defn- thrown-ex-data
  "The ex-data of the ExceptionInfo `thunk` throws, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

(defn- invalid-target? [thunk]
  (= :rf.reply/invalid-target (:rf.error/kind (thrown-ex-data thunk))))

;; ---------------------------------------------------------------------------
;; The reply map.
;; ---------------------------------------------------------------------------

(deftest reply-map-schema
  (testing "exactly the five statuses are valid"
    (is (= #{:ok :partial :error :cancelled :stale} rf.reply/statuses)))
  (testing "each status's value/error conventions: a row expecting ::valid must
            pass `valid-reply?`; any other row names the problem
            `validate-reply` must report"
    (doseq [[label reply expected]
            [["no :status" {:value 1} :rf.reply/missing-status]
             ["an out-of-vocabulary :status" {:status :done :value 1} :rf.reply/invalid-status]
             [":ok with a :value and an epoch-ms timestamp"
              {:status :ok :value {:title "Welcome"} :completed-at 1781078400456} ::valid]
             [":ok without a :value" {:status :ok} :rf.reply/ok-missing-value]
             [":ok carrying an :error" {:status :ok :value 1 :error {:kind :x}} :rf.reply/ok-has-error]
             ;; A present-but-nil :error is still present: :ok must omit it.
             [":ok with a nil :error placeholder" {:status :ok :value 1 :error nil} :rf.reply/ok-has-error]
             [":error with a family error map" {:status :error :error {:kind :rf.http/http-5xx}} ::valid]
             [":error without an :error" {:status :error} :rf.reply/error-missing-error]
             [":error map without a :kind" {:status :error :error {:no :kind}} :rf.reply/error-not-family-map]
             [":partial with a value and a family error"
              {:status :partial
               :value  {:user {:name "Ada"}}
               :error  {:kind :rf.graphql/partial-success :errors [{:message "field x denied"}]}}
              ::valid]
             [":partial without a :value" {:status :partial :error {:kind :x}} :rf.reply/partial-missing-value]
             [":partial without an :error" {:status :partial :value 1} :rf.reply/partial-missing-error]
             [":partial error map without a :kind" {:status :partial :value 1 :error {:no :kind}}
              :rf.reply/error-not-family-map]
             [":cancelled with a reason and the marker"
              {:status :cancelled :rf.reply/cancel-reason :user :cancelled? true} ::valid]
             [":cancelled with compatibility :error data"
              {:status                 :cancelled
               :rf.reply/cancel-reason :actor-destroyed
               :cancelled?             true
               :error                  {:kind :rf.http/aborted :reason :actor-destroyed}}
              ::valid]
             [":cancelled without a reason" {:status :cancelled :cancelled? true}
              :rf.reply/cancelled-missing-reason]
             [":cancelled with a reason alone, no :cancelled? true marker"
              {:status :cancelled :rf.reply/cancel-reason :user} :rf.reply/cancelled-missing-marker]
             [":cancelled? false on a :cancelled reply is contradictory"
              {:status :cancelled :rf.reply/cancel-reason :user :cancelled? false}
              :rf.reply/cancelled-missing-marker]
             [":stale with the flag and a reason"
              {:status :stale :stale? true :rf.reply/stale-reason :generation-mismatch} ::valid]
             [":stale without :stale? true" {:status :stale :rf.reply/stale-reason :x}
              :rf.reply/stale-missing-flag]
             [":stale without a reason" {:status :stale :stale? true} :rf.reply/stale-missing-reason]
             [":stale carrying a :value"
              {:status :stale :stale? true :rf.reply/stale-reason :x :value 1} :rf.reply/stale-has-value]
             ["a closed-set :rf.reply/work-status"
              {:status :error :error {:kind :rf.http/timeout} :rf.reply/work-status :timed-out} ::valid]
             ["an out-of-set :rf.reply/work-status" {:status :ok :value 1 :rf.reply/work-status :weird}
              :rf.reply/invalid-work-status]]]
      (testing label
        (if (= ::valid expected)
          (is (rf.reply/valid-reply? reply) (str (rf.reply/validate-reply reply)))
          (is (some #(= expected (:rf.reply/problem %)) (rf.reply/validate-reply reply))))))))

(deftest data-only-invariant-no-host-handles
  (testing "a host handle anywhere in a reply is reported at its exact path: a
            fn, a host Date (a durable timestamp is an epoch-ms long) and a
            host RegExp (not EDN)"
    (doseq [[label reply path]
            [["a fn nested in :value" {:status :ok :value {:a {:cb (fn [] 1)}}} [:value :a :cb]]
             ["a host Date"
              {:status :ok :value {:settled-at #?(:cljs (js/Date.) :clj (java.util.Date.))}}
              [:value :settled-at]]
             ["a host RegExp"
              {:status :error
               :error  {:kind :x :re #?(:cljs (js/RegExp. "x") :clj (java.util.regex.Pattern/compile "x"))}}
              [:error :re]]]]
      (testing label
        (is (= path (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %))
                          (rf.reply/validate-reply reply))))))))

;; ---------------------------------------------------------------------------
;; The reply target and completion.
;; ---------------------------------------------------------------------------

(deftest malformed-target-fails-closed
  (testing "a target that is not an event-vector prefix fails closed at
            normalization rather than becoming a bogus dispatch: a descriptor
            without :event, an empty vector, a non-keyword head, and a value
            that is neither vector nor map"
    (doseq [target [{} [] [42 :arg] :x]]
      (is (invalid-target? #(rf.reply/normalize-target target)) (pr-str target)))))

(deftest durable-target-is-data-only
  (testing "durable projection normalizes a data target, strips the ephemeral
            mapping fn, and keeps nil as nil"
    (is (= {:event [:x] :delivery :append :suppress {:g 1}}
           (rf.reply/durable-target {:event [:x] :suppress {:g 1}})))
    (is (= {:event [:x 1] :delivery :append}
           (rf.reply/durable-target (rf.reply/map-completed-event identity [:x 1]))))
    (is (nil? (rf.reply/durable-target nil))))
  (testing "a host handle in a public field fails loud, naming its path"
    (is (= {:rf.error/kind :rf.reply/non-data-target :path [:suppress :cb]}
           (select-keys (thrown-ex-data #(rf.reply/durable-target {:event [:x] :suppress {:cb (fn [] 1)}}))
                        [:rf.error/kind :path])))))

(deftest completion-appends-reply
  (let [reply {:status :ok :value {:title "Welcome"}}]
    (is (= [:article/load-replied {:id 42} reply]
           (rf.reply/complete [:article/load-replied {:id 42}] reply)))
    (is (nil? (rf.reply/complete nil reply)) "a nil target delivers nothing")))

(deftest completion-keeps-target-vector-metadata
  (testing "the reply is appended to the target vector as written, so its
            metadata travels with the completed event, including through the
            durable form resources and mutations store"
    (let [reply  {:status :ok :value 1}
          tagged (with-meta [:article/load-replied {:id 42}] {:app/tag :article})]
      (is (= {:app/tag :article} (meta (rf.reply/complete tagged reply))))
      (is (= {:app/tag :article} (meta (rf.reply/complete (rf.reply/durable-target tagged) reply))))
      (is (invalid-target? #(rf.reply/complete '(:article/load-replied {:id 42}) reply))
          "a list target, which conj would prepend to, fails closed"))))

;; ---------------------------------------------------------------------------
;; The functor laws (Managed-Effects §Reply mapping and the functor law).
;; ---------------------------------------------------------------------------

(def ^:private target {:event [:article/replied {:id 42}] :delivery :append})

(def ^:private a-reply {:status :ok :value {:id 42}})

(deftest functor-naturality-law
  (testing "(complete (map-completed-event f t) r) == (f (complete t r))"
    (let [f (fn [event] [:parent/relay event])]
      (is (= (f (rf.reply/complete target a-reply))
             (rf.reply/complete (rf.reply/map-completed-event f target) a-reply))))))

(deftest functor-composition-law
  (testing "(map-completed-event f (map-completed-event g t)) completes as
            (map-completed-event (comp f g) t); f and g do not commute, so a
            composition in the wrong order fails"
    (let [f (fn [e] [:f e])
          g (fn [e] [:g e])]
      (is (= (rf.reply/complete (rf.reply/map-completed-event (comp f g) target) a-reply)
             (rf.reply/complete (rf.reply/map-completed-event f (rf.reply/map-completed-event g target))
                                a-reply))))))

;; ---------------------------------------------------------------------------
;; Stale suppression: the correctness boundary.
;; ---------------------------------------------------------------------------

(deftest stale-gate-check
  (testing "matching correlation ⇒ not stale; superseded ⇒ stale"
    (is (false? (rf.reply/stale? {:generation 4} {:generation 4})))
    (is (true?  (rf.reply/stale? {:generation 4} {:generation 5})))
    (is (false? (rf.reply/stale? {:work/id :w :generation 4}
                                 {:work/id :w :generation 4 :extra :ignored}))
        "extra current keys are ignored — the carried gate's key set governs")
    (is (false? (rf.reply/stale? nil nil)) "no gate ⇒ nothing to supersede")
    (is (true?  (rf.reply/stale? {:generation 4} nil)) "current gone ⇒ stale")))

(deftest suppress-extra-cannot-override-stale-boundary
  (testing "threading a natural success reply as `extra` still yields a stale
            reply: status, flag and work-status are forced, :value is
            stripped, the default stale reason is supplied, and identity facts
            ride verbatim"
    (let [{:keys [reply deliver?]}
          (rf.reply/suppress nil {:g 1} {:g 2}
                             {:status               :ok
                              :value                {:title "should-be-stripped"}
                              :rf.reply/work-status :completed
                              :work/id              [:rf.work/http :req 1 1]
                              :work/kind            :http
                              :rf.frame/id          :app/main})]
      (is (false? deliver?))
      (is (= {:status                :stale
              :stale?                true
              :rf.reply/stale-reason :rf.reply/correlation-mismatch
              :rf.reply/work-status  :suppressed
              :work/id               [:rf.work/http :req 1 1]
              :work/kind             :http
              :rf.frame/id           :app/main}
             reply))
      (is (rf.reply/valid-reply? reply) (str (rf.reply/validate-reply reply))))))

(deftest suppress-is-universally-non-delivering
  (testing "no target makes a stale outcome deliver, not even one carrying
            :dispatch-stale? true and a forged stale-authority datum"
    (is (false? (:deliver? (rf.reply/suppress {:event                          [:app/replied]
                                               :dispatch-stale?                true
                                               :re-frame.reply/stale-authority true}
                                              {:g 1} {:g 2}))))))

;; ---------------------------------------------------------------------------
;; Trace summaries and the thrown-error shape.
;; ---------------------------------------------------------------------------

(deftest trace-summary-keeps-identity-facts-elides-wire-slots
  (testing "with no frame reachable the wire slots fail closed while identity
            facts ride verbatim"
    (is (= {:status :ok :value :rf/redacted}
           (rf.reply/trace-summary {:status :ok :value {:secret "x"}})))))

(deftest reply-throws-carry-canonical-rf-error-id
  (testing "every reply throw carries its canonical :rf.error/id beside the
            reply-specific :rf.error/kind, and names the :rf/reply-to surface"
    (doseq [[label thunk category error-id]
            [["invalid target"
              #(rf.reply/normalize-target {:event :x})
              :rf.reply/invalid-target :rf.error/reply-invalid-target]
             ["non-map reply"
              #(rf.reply/validate-reply 42)
              :rf.reply/non-map-reply :rf.error/reply-non-map-reply]
             ["unknown delivery mode"
              #(rf.reply/complete {:event [:x] :delivery :weird} {:status :ok})
              :rf.reply/unknown-delivery :rf.error/reply-unknown-delivery]]]
      (testing label
        (is (= {:rf.error/id error-id :rf.error/kind category :where :rf/reply-to}
               (select-keys (thrown-ex-data thunk) [:rf.error/id :rf.error/kind :where])))))))

(deftest bounded-walk-finds-a-handle-only-within-budget
  (testing "a budget that reaches the handle finds its path; one that runs out
            first reports nothing — a false negative, the safe direction for
            the advisory payload lint — rather than over-running"
    (let [payload {:a {:b {:c (fn [] nil)}}}]
      (is (= [:a :b :c] (rf.reply/walk-find-host-handle-bounded payload 500)))
      (is (nil? (rf.reply/walk-find-host-handle-bounded payload 1))))))
