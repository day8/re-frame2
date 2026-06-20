(ns re-frame.reply-test
  "Conformance for the EP-0011 uniform-reply-envelope substrate
  (`re-frame.reply`). Pins the three conformance groups EP-0011
  §Validation requires of the shared substrate slice:

    1. reply-map SCHEMA — exactly one valid `:status`; the per-status
       value/error conventions incl `:partial` (usable value AND
       structured error); the data-only invariant (NO host handles).
    2. functor LAWS for reply-target mapping — identity + composition,
       plus the naturality law `(complete (map-completed-event f t) r) ==
       (f (complete t r))`, and the proof that mapping changes ONLY the
       completed event (not work id / status / cancellation / stale /
       tracing).
    3. STALE SUPPRESSION — the helper suppresses the app target (does NOT
       deliver) AND records the suppressed ledger/trace outcome carrying
       the carried + current correlation.

  Canonical contract: `spec/Managed-Effects.md` §The uniform reply
  envelope. Pure substrate — no runtime fixture needed."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.error :as error]
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
              (reply/validate-reply {:status :ok :value 1 :error {:kind :x}}))))
  (testing "a PRESENT :error key on :ok — including a nil placeholder — is rejected (rf2-o7pqbm finding 3)"
    ;; The contract says :error is ABSENT for :ok (omit optional fields when
    ;; absent rather than fill a nil sentinel). A {:status :ok :error nil}
    ;; reply previously slipped through because validation rejected only
    ;; `(some? error)`; it now rejects a present-but-nil :error too.
    (is (some #(= :rf.reply/ok-has-error (:rf.reply/problem %))
              (reply/validate-reply {:status :ok :value 1 :error nil}))
        "{:status :ok :error nil} fails — :error should be OMITTED on :ok, not nil-filled")
    (is (reply/valid-reply? {:status :ok :value 1})
        "the well-formed shape OMITS :error entirely")))

(deftest error-conventions
  (testing ":error requires :error as a family error MAP carrying a :kind"
    (is (reply/valid-reply? {:status :error :error {:kind :rf.http/http-5xx}}))
    (is (some #(= :rf.reply/error-missing-error (:rf.reply/problem %))
              (reply/validate-reply {:status :error})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (reply/validate-reply {:status :error :error {:no :kind}}))
        "a map without :kind is not a family error"))
  (testing "a LOOSE SCALAR :error is rejected — every family error is a structured {:kind …} map"
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (reply/validate-reply {:status :error :error :rf.http/http-5xx}))
        "a bare keyword :error fails loud — the closed contract demands the {:kind …} shape")
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (reply/validate-reply {:status :error :error "boom"}))
        "a bare string :error fails loud too")))

(deftest partial-conventions
  (testing ":partial carries BOTH usable :value AND a structured family :error MAP with a :kind"
    (is (reply/valid-reply?
          {:status :partial
           :value  {:user {:name "Ada"}}
           :error  {:kind :rf.graphql/partial-success
                    :errors [{:message "field x denied"}]}}))
    (is (some #(= :rf.reply/partial-missing-value (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :error {:kind :x}})))
    (is (some #(= :rf.reply/partial-missing-error (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :value 1})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :value 1 :error {:no :kind}})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (reply/validate-reply {:status :partial :value 1 :error :loose-scalar}))
        "a loose scalar :error on a :partial is rejected just like on :error")))

(deftest cancelled-conventions
  (testing ":cancelled requires :cancel/reason AND the :cancelled? true marker; :error MAY carry compatibility data"
    (is (reply/valid-reply? {:status :cancelled :cancel/reason :user :cancelled? true}))
    (is (reply/valid-reply? {:status        :cancelled
                             :cancel/reason :actor-destroyed
                             :cancelled?    true
                             :error         {:kind :rf.http/aborted :reason :actor-destroyed}}))
    (is (some #(= :rf.reply/cancelled-missing-reason (:rf.reply/problem %))
              (reply/validate-reply {:status :cancelled :cancelled? true})))
    (is (some #(= :rf.reply/cancelled-missing-marker (:rf.reply/problem %))
              (reply/validate-reply {:status :cancelled :cancel/reason :user}))
        "a :cancel/reason alone is NOT enough — cancellation is a positive :cancelled? true fact")
    (is (some #(= :rf.reply/cancelled-missing-marker (:rf.reply/problem %))
              (reply/validate-reply {:status :cancelled :cancel/reason :user :cancelled? false}))
        ":cancelled? false on a :cancelled reply is contradictory and fails loud")))

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

(deftest data-only-invariant-rejects-non-edn-host-objects
  ;; rf2-o7pqbm finding 4 — the host-handle docstring named JS Date / RegExp
  ;; (and the JVM counterparts) among the rejected host objects, but the
  ;; predicate never checked them. The detector and its documented contract
  ;; are now ALIGNED: a Date / RegExp (a non-EDN host object that neither
  ;; round-trips through the EDN reader nor compares by value) is a host handle
  ;; and fails the data-only invariant on BOTH runtimes — a durable timestamp
  ;; is an epoch-millisecond long (EP-0010), never a host Date.
  (testing "a host Date in the reply is a host handle (CLJS js/Date, JVM java.util.Date)"
    (let [d #?(:cljs (js/Date.) :clj (java.util.Date.))]
      (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
                (reply/validate-reply {:status :ok :value {:settled-at d}}))
          "a host Date must not ride a data-only reply — use an epoch-ms long")
      (let [probs (reply/validate-reply {:status :ok :value {:settled-at d}})
            path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
        (is (= [:value :settled-at] path) "the problem reports the exact path to the Date"))))
  (testing "a host RegExp in the reply is a host handle (CLJS js/RegExp, JVM java.util.regex.Pattern)"
    (let [re #?(:cljs (js/RegExp. "x") :clj (java.util.regex.Pattern/compile "x"))]
      (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
                (reply/validate-reply {:status :error :error {:kind :x :re re}}))
          "a host RegExp must not ride a data-only reply")
      (let [probs (reply/validate-reply {:status :error :error {:kind :x :re re}})
            path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
        (is (= [:error :re] path) "the problem reports the exact path to the RegExp"))))
  (testing "the durable-target guard rejects a non-EDN host object in a public field too"
    (let [d #?(:cljs (js/Date.) :clj (java.util.Date.))]
      (try
        (reply/durable-target {:event [:x] :suppress {:at d}})
        (is false "expected durable-target to reject a host Date in :suppress")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
          (is (= :rf.reply/non-data-target (:rf.error/kind (ex-data e))))
          (is (= [:suppress :at] (:path (ex-data e))))))))
  (testing "plain EDN — including an epoch-ms long instant — still passes"
    (is (reply/valid-reply?
          {:status :ok :value {:title "x"} :completed-at 1781078400456}))))

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
;; Group 1b'' — MALFORMED target rejection (rf2-o7pqbm finding 1+2). The
;; descriptor's `:event` is REQUIRED and is an event-vector prefix
;; (Managed-Effects §The reply target). A descriptor missing `:event`, or
;; carrying a non-vector / empty / non-keyword-headed `:event`, must FAIL
;; CLOSED at normalization rather than travel on to `complete` and become a
;; bogus dispatch shape. `map-completed-event` must preserve the nil/no-continuation
;; semantics rather than fabricate an eventless `{::post f}` descriptor.
;; ---------------------------------------------------------------------------

(defn- invalid-target? [thunk]
  (try (thunk) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (= :rf.reply/invalid-target (:rf.error/kind (ex-data e))))))

(deftest malformed-target-fails-closed
  (testing "a descriptor with NO :event is rejected (not silently passed to complete)"
    (is (invalid-target? #(reply/normalize-target {}))
        "{} carries no event — it cannot become a dispatch shape")
    (is (invalid-target? #(reply/normalize-target {:delivery :append :suppress {:g 1}}))
        "a descriptor with gates but no :event is still malformed"))
  (testing "a descriptor whose :event is nil / a bare keyword / not a vector is rejected"
    (is (invalid-target? #(reply/normalize-target {:event nil}))
        "{:event nil} would (vec nil) into a garbage event")
    (is (invalid-target? #(reply/normalize-target {:event :x}))
        "{:event :x} is a bare keyword, not an event-vector prefix")
    (is (invalid-target? #(reply/normalize-target {:event "boom"})))
    (is (invalid-target? #(reply/normalize-target {:event {:id 1}}))))
  (testing "an EMPTY or non-keyword-headed event vector is rejected"
    (is (invalid-target? #(reply/normalize-target []))
        "an empty vector has no event id to dispatch")
    (is (invalid-target? #(reply/normalize-target {:event []})))
    (is (invalid-target? #(reply/normalize-target [42 :arg]))
        "the head must be a keyword event id, not a number")
    (is (invalid-target? #(reply/normalize-target ["not-a-keyword"]))))
  (testing "a non-vector / non-map target is rejected"
    (is (invalid-target? #(reply/normalize-target :x)))
    (is (invalid-target? #(reply/normalize-target 42)))
    (is (invalid-target? #(reply/normalize-target "boom"))))
  (testing "a WELL-FORMED target still normalizes (the guard rejects only malformed shapes)"
    (is (= {:event [:x 1] :delivery :append} (reply/normalize-target [:x 1])))
    (is (= {:event [:x] :delivery :append} (reply/normalize-target {:event [:x]}))))
  (testing "the malformed-target rejection propagates through complete / target->short-form"
    (is (invalid-target? #(reply/complete {:event :x} {:status :ok :value 1}))
        "complete fails closed on a malformed descriptor (never (vec :x))")
    (is (invalid-target? #(reply/target->short-form {})))))

(deftest map-completed-event-preserves-nil-no-continuation
  (testing "mapping a nil target stays nil — NOT a bogus {::post f} eventless descriptor"
    (is (nil? (reply/map-completed-event identity nil)))
    (is (nil? (reply/map-completed-event (fn [e] [:wrap e]) nil))
        "mapping the absence of a continuation is still the absence of a continuation")
    (is (nil? (reply/complete (reply/map-completed-event (fn [e] [:wrap e]) nil)
                              {:status :ok :value 1}))
        "and completing that mapped-nil target yields nil (no delivery)"))
  (testing "mapping a well-formed target still relocates it (the nil guard does not weaken mapping)"
    (let [mapped (reply/map-completed-event (fn [e] [:parent e]) [:x {:id 1}])]
      (is (some? mapped))
      (is (= [:parent [:x {:id 1} {:status :ok :value 7}]]
             (reply/complete mapped {:status :ok :value 7}))))))

;; ---------------------------------------------------------------------------
;; Group 1b' — the reply-target-as-data contract (rf2-r16hfc item 1). A
;; normalized target may carry the EPHEMERAL non-data slots (`::post`, the
;; functor accumulator fn; `::stale-authority`, the capability marker) while
;; in-flight, but a target that could become DURABLE must be data-only.
;; `durable-target` strips the ephemerals and asserts no host handle leaks
;; into a persisted target; `data-only-target?` is the predicate.
;; ---------------------------------------------------------------------------

(deftest durable-target-is-data-only
  (testing "a plain data target is data-only and survives durable projection unchanged"
    (is (true? (reply/data-only-target? [:x 1])))
    (is (true? (reply/data-only-target? {:event [:x] :suppress {:g 1} :dispatch-stale? false})))
    (is (= {:event [:x] :delivery :append :suppress {:g 1}}
           (reply/durable-target {:event [:x] :suppress {:g 1}})))
    (is (nil? (reply/durable-target nil)) "nil target ⇒ nil (nothing to persist)"))
  (testing "a MAPPED target carries the ::post fn — NOT data-only — and durable projection strips it"
    (let [mapped (reply/map-completed-event (fn [e] e) [:x 1])]
      (is (false? (reply/data-only-target? mapped))
          "the functor accumulator is a fn — a mapped target is not safe to persist")
      (let [durable (reply/durable-target mapped)]
        (is (true? (reply/data-only-target? durable)) "stripping ::post restores data-only")
        (is (not (reply/stale-authority? durable))))))
  (testing "an AUTHORISED target carries the capability marker — NOT data-only — and is stripped"
    (let [authd (reply/with-stale-authority {:event [:x] :dispatch-stale? true})]
      (is (false? (reply/data-only-target? authd))
          "the ::stale-authority capability must not persist into a durable target")
      (is (false? (reply/stale-authority? (reply/durable-target authd)))
          "durable projection strips the capability — a persisted target re-acquires no authority")
      (is (= {:event [:x] :delivery :append :dispatch-stale? true}
             (reply/durable-target authd)))))
  (testing "durable-target FAILS LOUD when a host handle hides in a PUBLIC field (an app/family bug)"
    ;; A function smuggled into :suppress (or any public slot) would leak a
    ;; non-serializable value into a durable reply target — reject it loudly.
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"must be data-only"
          (reply/durable-target {:event [:x] :suppress {:cb (fn [] 1)}})))
    (try
      (reply/durable-target {:event [:x] :suppress {:cb (fn [] 1)}})
      (is false "expected durable-target to throw")
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
        (is (= :rf.reply/non-data-target (:rf.error/kind (ex-data e))))
        (is (= [:suppress :cb] (:path (ex-data e))) "the failure reports the exact path to the handle")))))

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
  (testing "(map-completed-event identity target) completes identically to target — identity law"
    (is (= (reply/complete target a-reply)
           (reply/complete (reply/map-completed-event identity target) a-reply)))))

(deftest functor-naturality-law
  (testing "(complete (map-completed-event f t) r) == (f (complete t r)) — the mapping law"
    (is (= (reply/complete (reply/map-completed-event select-article-event target) a-reply)
           (select-article-event (reply/complete target a-reply))))
    (is (= (reply/complete (reply/map-completed-event wrap-in-parent target) a-reply)
           (wrap-in-parent (reply/complete target a-reply))))))

(deftest functor-composition-law
  (testing "(map-completed-event f (map-completed-event g t)) == (map-completed-event (comp f g) t) — composition law"
    ;; Both transforms preserve the appended-reply event shape, so they
    ;; compose in either order — exercising composition both ways.
    (let [f select-article-event
          g retarget-event-id]
      (is (= (reply/complete (reply/map-completed-event f (reply/map-completed-event g target)) a-reply)
             (reply/complete (reply/map-completed-event (comp f g) target) a-reply)))
      (is (= (reply/complete (reply/map-completed-event g (reply/map-completed-event f target)) a-reply)
             (reply/complete (reply/map-completed-event (comp g f) target) a-reply)))
      ;; And the composed result is the expected event: renamed id + selected value.
      (is (= [:parent/relay {:id 42} {:status :ok :value {:id 42 :title "Welcome"}
                                      :work/id [:rf.work/http :article/by-id 42 1]}]
             (reply/complete (reply/map-completed-event (comp f g) target) a-reply))))))

(deftest mapping-changes-only-the-event
  (testing "mapping the target does NOT change the reply's work id / status / correlation"
    (let [mapped    (reply/map-completed-event select-article-event target)
          completed (reply/complete mapped a-reply)
          delivered (peek completed)]
      ;; The COMPLETED EVENT changed (value→article); the reply's identity facts did not.
      (is (= [:rf.work/http :article/by-id 42 1] (:work/id delivered)))
      (is (= :ok (:status delivered)))
      ;; And issuance/correlation are unaffected — `map-completed-event` stores no work-id /
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

(deftest suppress-extra-cannot-override-stale-boundary
  (testing "rf2-waawic — `extra` CANNOT override the stale boundary: threading a
            natural success reply as extra still produces a valid :status :stale
            reply with NO :value (the correctness boundary is structural)"
    ;; The dangerous caller mistake the guardrail closes: passing a complete
    ;; natural-completion reply (status :ok, a :value, work-status :completed)
    ;; as `extra`. Before the fix `merge` let those win; now the stale fields
    ;; are forced and :value is stripped.
    (let [{:keys [reply deliver?]}
          (reply/suppress nil {:g 1} {:g 2}
                          {:status      :ok
                           :value       {:title "should-be-stripped"}
                           :work/status :completed
                           :work/id     [:rf.work/http :req 1 1]
                           :work/kind   :http
                           :rf.frame/id :app/main})]
      (is (= :stale (:status reply)) "status forced to :stale, not the :ok in extra")
      (is (true? (:stale? reply)))
      (is (= :suppressed (:work/status reply)) "work-status forced, not :completed")
      (is (not (contains? reply :value)) "the :value in extra is stripped — a stale reply MUST NOT carry one")
      (is (false? deliver?))
      ;; identity facts from extra still ride verbatim
      (is (= [:rf.work/http :req 1 1] (:work/id reply)))
      (is (= :http (:work/kind reply)))
      (is (= :app/main (:rf.frame/id reply)))
      ;; the result validates as a conformant stale reply
      (is (reply/valid-reply? reply) (str (reply/validate-reply reply))))))

(deftest suppress-dispatch-stale-opt-in
  (testing "the default — every target, app or framework — is NON-delivery of a stale reply"
    (is (false? (:deliver? (reply/suppress {:event [:x]} {:g 1} {:g 2})))
        "no :dispatch-stale? ⇒ not delivered")
    (is (false? (:deliver? (reply/suppress (reply/with-stale-authority {:event [:x]}) {:g 1} {:g 2})))
        "authority WITHOUT :dispatch-stale? ⇒ still not delivered (the capability alone does not opt in)"))
  (testing "a FRAMEWORK/TOOL target — :dispatch-stale? true stamped with stale-authority — IS delivered"
    (is (true? (:deliver? (reply/suppress (reply/with-stale-authority {:event [:x] :dispatch-stale? true})
                                          {:g 1} {:g 2})))
        "the framework/tool capability + the explicit opt-in lets a test/tool target receive the stale envelope")
    (is (false? (:deliver? (reply/suppress (reply/with-stale-authority {:event [:x] :dispatch-stale? false})
                                           {:g 1} {:g 2})))
        ":dispatch-stale? false on an authorised target ⇒ not delivered")))

;; ---------------------------------------------------------------------------
;; Group 3b — :dispatch-stale? AUTHORITY: framework/tool-only (rf2-636pkr).
;; The substrate is pure (no caller identity), so stale-delivery authority is
;; a namespaced-private capability marker only framework/tool code can attach
;; via `with-stale-authority`. An APP target — built from public `:rf/reply-to`
;; data, which cannot name the private marker — cannot grant itself stale
;; delivery; trying to (`:dispatch-stale? true` with no authority) FAILS LOUD.
;; ---------------------------------------------------------------------------

(deftest dispatch-stale-app-target-cannot-opt-in
  (testing "an APP target (:dispatch-stale? true, NO authority) cannot enable stale delivery — fails loud"
    ;; This is the security boundary: an app's :rf/reply-to descriptor can set
    ;; :dispatch-stale? true (it is plain data), but it CANNOT carry the
    ;; framework-private authority marker, so suppress must refuse it loudly
    ;; rather than silently deliver a stale envelope into app state.
    (let [app-target {:event [:app/replied] :dispatch-stale? true}]
      (is (thrown-with-msg?
            #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
            #"restricted to framework"
            (reply/suppress app-target {:g 1} {:g 2}))
          "an app target that sets :dispatch-stale? true is rejected — it has no stale-delivery authority")
      (try
        (reply/suppress app-target {:g 1} {:g 2})
        (is false "expected suppress to throw")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
          (is (= :rf.reply/unauthorized-stale-delivery (:rf.error/kind (ex-data e)))
              "the failure carries the closed error kind")))))
  (testing "the SAME descriptor, once stamped with framework/tool authority, IS allowed"
    (is (true? (:deliver?
                 (reply/suppress (reply/with-stale-authority {:event [:app/replied] :dispatch-stale? true})
                                 {:g 1} {:g 2})))
        "wrapping with with-stale-authority is the ONLY way to legitimately opt in"))
  (testing "stale-authority? reports the capability; the public descriptor never carries it"
    (is (false? (reply/stale-authority? {:event [:x] :dispatch-stale? true}))
        "a plain (app) descriptor has no authority")
    (is (true? (reply/stale-authority? (reply/with-stale-authority {:event [:x]})))
        "with-stale-authority stamps the capability")))

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

;; ---------------------------------------------------------------------------
;; Group 5 — Spec 009 thrown-error shape (rf2-tqlwzr). Every reply throw now
;; routes through the central builder: it exposes the canonical `:rf.error/id`
;; discriminator (alongside the preserved reply-specific `:rf.error/kind`), a
;; human `:reason` sentence, a `:where`, and a message that LEADS with the
;; sentence and TRAILS with the `[:rf.error/<id>]` token (never a bare keyword).
;; ---------------------------------------------------------------------------

(defn- catch-ex-info [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e)))

(deftest reply-throws-carry-canonical-rf-error-id
  (testing "the standardized :rf.error/id discriminator (the :rf.error/reply-*
            projection) is present AND its :rf.error/kind preserves the
            reply-specific category on every reply throw"
    (doseq [[label thunk category error-id]
            [["invalid short-form target"
              #(reply/normalize-target {:event :x})
              :rf.reply/invalid-target :rf.error/reply-invalid-target]
             ["non-map reply"
              #(reply/validate-reply 42)
              :rf.reply/non-map-reply :rf.error/reply-non-map-reply]
             ["unknown delivery mode"
              #(reply/complete {:event [:x] :delivery :weird} {:status :ok})
              :rf.reply/unknown-delivery :rf.error/reply-unknown-delivery]]]
      (let [e    (catch-ex-info thunk)
            data (ex-data e)]
        (is (some? e) (str label " throws"))
        (is (= error-id (:rf.error/id data))
            (str label " exposes the canonical :rf.error/* discriminator"))
        (is (= category (:rf.error/kind data))
            (str label " preserves the reply-specific :rf.error/kind"))
        (is (= :rf/reply-to (:where data))
            (str label " names the public :rf/reply-to surface"))))))

(deftest reply-throw-message-is-actionable-not-a-bare-keyword
  ;; one actionable message path: a non-map reply's message LEADS with the
  ;; human sentence and TRAILS with the [:rf.error/<id>] token.
  (let [e   (catch-ex-info #(reply/validate-reply 42))
        msg (ex-message e)]
    (is (some? e))
    (is (not (error/keyword-only-message? msg))
        "the message is a human sentence, never a bare keyword (Spec 009 rule 1)")
    (is (error/message-has-id-token? msg)
        "the message carries the [:rf.error/<id>] greppability token (rule 4)")
    (is (string? (:reason (ex-data e)))
        ":reason is the required human sentence")))
