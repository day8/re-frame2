(ns re-frame.reply-cljs-test
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
            [re-frame.error :as rf.error]
            [re-frame.reply :as rf.reply]))

;; ---------------------------------------------------------------------------
;; Group 1 — reply-map schema.
;; ---------------------------------------------------------------------------

(deftest closed-status-vocabulary
  (testing "exactly the five statuses are valid"
    (is (= #{:ok :partial :error :cancelled :stale} rf.reply/statuses)))
  (testing "a reply with no :status is invalid"
    (is (some #(= :rf.reply/missing-status (:rf.reply/problem %))
              (rf.reply/validate-reply {:value 1}))))
  (testing "a reply with an out-of-vocabulary :status is invalid"
    (is (some #(= :rf.reply/invalid-status (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :done :value 1}))))
  (testing "exactly one valid :status passes"
    (is (rf.reply/valid-reply? {:status :ok :value {:title "Welcome"}}))))

(deftest ok-conventions
  (testing ":ok requires :value and forbids :error"
    (is (rf.reply/valid-reply? {:status :ok :value 42}))
    (is (some #(= :rf.reply/ok-missing-value (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok})))
    (is (some #(= :rf.reply/ok-has-error (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value 1 :error {:kind :x}}))))
  (testing "a PRESENT :error key on :ok — including a nil placeholder — is rejected (rf2-o7pqbm finding 3)"
    ;; The contract says :error is ABSENT for :ok (omit optional fields when
    ;; absent rather than fill a nil sentinel). A {:status :ok :error nil}
    ;; reply previously slipped through because validation rejected only
    ;; `(some? error)`; it now rejects a present-but-nil :error too.
    (is (some #(= :rf.reply/ok-has-error (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value 1 :error nil}))
        "{:status :ok :error nil} fails — :error should be OMITTED on :ok, not nil-filled")
    (is (rf.reply/valid-reply? {:status :ok :value 1})
        "the well-formed shape OMITS :error entirely")))

(deftest error-conventions
  (testing ":error requires :error as a family error MAP carrying a :kind"
    (is (rf.reply/valid-reply? {:status :error :error {:kind :rf.http/http-5xx}}))
    (is (some #(= :rf.reply/error-missing-error (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :error})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :error :error {:no :kind}}))
        "a map without :kind is not a family error"))
  (testing "a LOOSE SCALAR :error is rejected — every family error is a structured {:kind …} map"
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :error :error :rf.http/http-5xx}))
        "a bare keyword :error fails loud — the closed contract demands the {:kind …} shape")
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :error :error "boom"}))
        "a bare string :error fails loud too")))

(deftest partial-conventions
  (testing ":partial carries BOTH usable :value AND a structured family :error MAP with a :kind"
    (is (rf.reply/valid-reply?
          {:status :partial
           :value  {:user {:name "Ada"}}
           :error  {:kind :rf.graphql/partial-success
                    :errors [{:message "field x denied"}]}}))
    (is (some #(= :rf.reply/partial-missing-value (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :partial :error {:kind :x}})))
    (is (some #(= :rf.reply/partial-missing-error (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :partial :value 1})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :partial :value 1 :error {:no :kind}})))
    (is (some #(= :rf.reply/error-not-family-map (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :partial :value 1 :error :loose-scalar}))
        "a loose scalar :error on a :partial is rejected just like on :error")))

(deftest cancelled-conventions
  (testing ":cancelled requires :rf.reply/cancel-reason AND the :cancelled? true marker; :error MAY carry compatibility data"
    (is (rf.reply/valid-reply? {:status :cancelled :rf.reply/cancel-reason :user :cancelled? true}))
    (is (rf.reply/valid-reply? {:status        :cancelled
                             :rf.reply/cancel-reason :actor-destroyed
                             :cancelled?    true
                             :error         {:kind :rf.http/aborted :reason :actor-destroyed}}))
    (is (some #(= :rf.reply/cancelled-missing-reason (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :cancelled :cancelled? true})))
    (is (some #(= :rf.reply/cancelled-missing-marker (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :cancelled :rf.reply/cancel-reason :user}))
        "a :rf.reply/cancel-reason alone is NOT enough — cancellation is a positive :cancelled? true fact")
    (is (some #(= :rf.reply/cancelled-missing-marker (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :cancelled :rf.reply/cancel-reason :user :cancelled? false}))
        ":cancelled? false on a :cancelled reply is contradictory and fails loud")))

(deftest stale-conventions
  (testing ":stale requires :stale? true + :rf.reply/stale-reason and carries NO :value"
    (is (rf.reply/valid-reply? {:status :stale :stale? true :rf.reply/stale-reason :generation-mismatch}))
    (is (some #(= :rf.reply/stale-missing-flag (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :stale :rf.reply/stale-reason :x})))
    (is (some #(= :rf.reply/stale-missing-reason (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :stale :stale? true})))
    (is (some #(= :rf.reply/stale-has-value (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :stale :stale? true :rf.reply/stale-reason :x :value 1}))
        "a stale reply MUST NOT mutate app state — carrying :value would invite it")))

(deftest work-status-vocabulary
  (testing ":rf.reply/work-status, when present, is in the closed operational set"
    (is (rf.reply/valid-reply? {:status :error :error {:kind :rf.http/timeout} :rf.reply/work-status :timed-out}))
    (is (some #(= :rf.reply/invalid-work-status (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value 1 :rf.reply/work-status :weird})))))

(deftest data-only-invariant-no-host-handles
  (testing "a fn anywhere in the reply is a host handle"
    (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value (fn [] 1)})))
    (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
              (rf.reply/validate-reply {:status :ok :value {:a {:b (fn [] 1)}}}))
        "host handles are found at any depth")
    (let [probs (rf.reply/validate-reply {:status :ok :value {:a {:cb (fn [] 1)}}})
          path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
      (is (= [:value :a :cb] path) "the problem reports the exact path to the handle")))
  (testing "a plain-data reply has no host-handle problem"
    (is (rf.reply/valid-reply?
          {:status       :ok
           :value        {:title "Welcome"}
           :work/id      [:rf.work/http :article/by-id 42 1]
           :work/kind    :http
           :rf.reply/work-status  :completed
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
                (rf.reply/validate-reply {:status :ok :value {:settled-at d}}))
          "a host Date must not ride a data-only reply — use an epoch-ms long")
      (let [probs (rf.reply/validate-reply {:status :ok :value {:settled-at d}})
            path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
        (is (= [:value :settled-at] path) "the problem reports the exact path to the Date"))))
  (testing "a host RegExp in the reply is a host handle (CLJS js/RegExp, JVM java.util.regex.Pattern)"
    (let [re #?(:cljs (js/RegExp. "x") :clj (java.util.regex.Pattern/compile "x"))]
      (is (some #(= :rf.reply/host-handle (:rf.reply/problem %))
                (rf.reply/validate-reply {:status :error :error {:kind :x :re re}}))
          "a host RegExp must not ride a data-only reply")
      (let [probs (rf.reply/validate-reply {:status :error :error {:kind :x :re re}})
            path  (some #(when (= :rf.reply/host-handle (:rf.reply/problem %)) (:path %)) probs)]
        (is (= [:error :re] path) "the problem reports the exact path to the RegExp"))))
  (testing "the durable-target guard rejects a non-EDN host object in a public field too"
    (let [d #?(:cljs (js/Date.) :clj (java.util.Date.))]
      (try
        (rf.reply/durable-target {:event [:x] :suppress {:at d}})
        (is false "expected durable-target to reject a host Date in :suppress")
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
          (is (= :rf.reply/non-data-target (:rf.error/kind (ex-data e))))
          (is (= [:suppress :at] (:path (ex-data e))))))))
  (testing "plain EDN — including an epoch-ms long instant — still passes"
    (is (rf.reply/valid-reply?
          {:status :ok :value {:title "x"} :completed-at 1781078400456}))))

;; ---------------------------------------------------------------------------
;; Group 1b — target normalization.
;; ---------------------------------------------------------------------------

(deftest target-normalization
  (testing "the public short form normalizes to a :delivery :append descriptor"
    (is (= {:event [:article/load-replied {:id 42}] :delivery :append}
           (rf.reply/normalize-target [:article/load-replied {:id 42}]))))
  (testing "the descriptor form defaults :delivery to :append"
    (is (= :append (:delivery (rf.reply/normalize-target
                                {:event [:x] :suppress {:generation 1}})))))
  (testing "normalization is idempotent and preserves gate fields"
    (let [d (rf.reply/normalize-target {:event [:x] :delivery :append
                                     :suppress {:route/nav-token "nav-7"}})]
      (is (= d (rf.reply/normalize-target d)))))
  (testing "short-form projection round-trips a plain target"
    (is (= [:x 1] (rf.reply/target->short-form [:x 1])))
    (is (= [:x 1] (rf.reply/target->short-form {:event [:x 1] :delivery :append}))))
  (testing "short-form projection keeps the descriptor when gates are present"
    (is (map? (rf.reply/target->short-form {:event [:x] :suppress {:g 1}}))))
  (testing "nil target ⇒ nil (no continuation)"
    (is (nil? (rf.reply/normalize-target nil)))))

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
    (is (invalid-target? #(rf.reply/normalize-target {}))
        "{} carries no event — it cannot become a dispatch shape")
    (is (invalid-target? #(rf.reply/normalize-target {:delivery :append :suppress {:g 1}}))
        "a descriptor with gates but no :event is still malformed"))
  (testing "a descriptor whose :event is nil / a bare keyword / not a vector is rejected"
    (is (invalid-target? #(rf.reply/normalize-target {:event nil}))
        "{:event nil} would (vec nil) into a garbage event")
    (is (invalid-target? #(rf.reply/normalize-target {:event :x}))
        "{:event :x} is a bare keyword, not an event-vector prefix")
    (is (invalid-target? #(rf.reply/normalize-target {:event "boom"})))
    (is (invalid-target? #(rf.reply/normalize-target {:event {:id 1}}))))
  (testing "an EMPTY or non-keyword-headed event vector is rejected"
    (is (invalid-target? #(rf.reply/normalize-target []))
        "an empty vector has no event id to dispatch")
    (is (invalid-target? #(rf.reply/normalize-target {:event []})))
    (is (invalid-target? #(rf.reply/normalize-target [42 :arg]))
        "the head must be a keyword event id, not a number")
    (is (invalid-target? #(rf.reply/normalize-target ["not-a-keyword"]))))
  (testing "a non-vector / non-map target is rejected"
    (is (invalid-target? #(rf.reply/normalize-target :x)))
    (is (invalid-target? #(rf.reply/normalize-target 42)))
    (is (invalid-target? #(rf.reply/normalize-target "boom"))))
  (testing "a WELL-FORMED target still normalizes (the guard rejects only malformed shapes)"
    (is (= {:event [:x 1] :delivery :append} (rf.reply/normalize-target [:x 1])))
    (is (= {:event [:x] :delivery :append} (rf.reply/normalize-target {:event [:x]}))))
  (testing "the malformed-target rejection propagates through complete / target->short-form"
    (is (invalid-target? #(rf.reply/complete {:event :x} {:status :ok :value 1}))
        "complete fails closed on a malformed descriptor (never (vec :x))")
    (is (invalid-target? #(rf.reply/target->short-form {})))))

(deftest map-completed-event-preserves-nil-no-continuation
  (testing "mapping a nil target stays nil — NOT a bogus {::post f} eventless descriptor"
    (is (nil? (rf.reply/map-completed-event identity nil)))
    (is (nil? (rf.reply/map-completed-event (fn [e] [:wrap e]) nil))
        "mapping the absence of a continuation is still the absence of a continuation")
    (is (nil? (rf.reply/complete (rf.reply/map-completed-event (fn [e] [:wrap e]) nil)
                              {:status :ok :value 1}))
        "and completing that mapped-nil target yields nil (no delivery)"))
  (testing "mapping a well-formed target still relocates it (the nil guard does not weaken mapping)"
    (let [mapped (rf.reply/map-completed-event (fn [e] [:parent e]) [:x {:id 1}])]
      (is (some? mapped))
      (is (= [:parent [:x {:id 1} {:status :ok :value 7}]]
             (rf.reply/complete mapped {:status :ok :value 7}))))))

;; ---------------------------------------------------------------------------
;; Group 1b' — the reply-target-as-data contract (rf2-r16hfc item 1). A
;; normalized target may carry the EPHEMERAL non-data slot `::post` (the
;; functor accumulator fn) while in-flight, but a target that could become
;; DURABLE must be data-only. `durable-target` strips the ephemeral and asserts
;; no host handle leaks into a persisted target; `data-only-target?` is the
;; predicate.
;; ---------------------------------------------------------------------------

(deftest durable-target-is-data-only
  (testing "a plain data target is data-only and survives durable projection unchanged"
    (is (true? (rf.reply/data-only-target? [:x 1])))
    (is (true? (rf.reply/data-only-target? {:event [:x] :suppress {:g 1}})))
    (is (= {:event [:x] :delivery :append :suppress {:g 1}}
           (rf.reply/durable-target {:event [:x] :suppress {:g 1}})))
    (is (nil? (rf.reply/durable-target nil)) "nil target ⇒ nil (nothing to persist)"))
  (testing "a MAPPED target carries the ::post fn — NOT data-only — and durable projection strips it"
    (let [mapped (rf.reply/map-completed-event (fn [e] e) [:x 1])]
      (is (false? (rf.reply/data-only-target? mapped))
          "the functor accumulator is a fn — a mapped target is not safe to persist")
      (let [durable (rf.reply/durable-target mapped)]
        (is (true? (rf.reply/data-only-target? durable)) "stripping ::post restores data-only")
        (is (= {:event [:x 1] :delivery :append} durable)))))
  (testing "durable-target FAILS LOUD when a host handle hides in a PUBLIC field (an app/family bug)"
    ;; A function smuggled into :suppress (or any public slot) would leak a
    ;; non-serializable value into a durable reply target — reject it loudly.
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
          #"must be data-only"
          (rf.reply/durable-target {:event [:x] :suppress {:cb (fn [] 1)}})))
    (try
      (rf.reply/durable-target {:event [:x] :suppress {:cb (fn [] 1)}})
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
             (rf.reply/complete [:article/load-replied {:id 42}] reply))))
    (testing "completion works through the descriptor form too"
      (is (= [:article/load-replied {:id 42} reply]
             (rf.reply/complete {:event [:article/load-replied {:id 42}] :delivery :append} reply))))
    (testing "nil target ⇒ nil (no delivery)"
      (is (nil? (rf.reply/complete nil reply))))))

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
    (is (= (rf.reply/complete target a-reply)
           (rf.reply/complete (rf.reply/map-completed-event identity target) a-reply)))))

(deftest functor-naturality-law
  (testing "(complete (map-completed-event f t) r) == (f (complete t r)) — the mapping law"
    (is (= (rf.reply/complete (rf.reply/map-completed-event select-article-event target) a-reply)
           (select-article-event (rf.reply/complete target a-reply))))
    (is (= (rf.reply/complete (rf.reply/map-completed-event wrap-in-parent target) a-reply)
           (wrap-in-parent (rf.reply/complete target a-reply))))))

(deftest functor-composition-law
  (testing "(map-completed-event f (map-completed-event g t)) == (map-completed-event (comp f g) t) — composition law"
    ;; Both transforms preserve the appended-reply event shape, so they
    ;; compose in either order — exercising composition both ways.
    (let [f select-article-event
          g retarget-event-id]
      (is (= (rf.reply/complete (rf.reply/map-completed-event f (rf.reply/map-completed-event g target)) a-reply)
             (rf.reply/complete (rf.reply/map-completed-event (comp f g) target) a-reply)))
      (is (= (rf.reply/complete (rf.reply/map-completed-event g (rf.reply/map-completed-event f target)) a-reply)
             (rf.reply/complete (rf.reply/map-completed-event (comp g f) target) a-reply)))
      ;; And the composed result is the expected event: renamed id + selected value.
      (is (= [:parent/relay {:id 42} {:status :ok :value {:id 42 :title "Welcome"}
                                      :work/id [:rf.work/http :article/by-id 42 1]}]
             (rf.reply/complete (rf.reply/map-completed-event (comp f g) target) a-reply))))))

(deftest mapping-changes-only-the-event
  (testing "mapping the target does NOT change the reply's work id / status / correlation"
    (let [mapped    (rf.reply/map-completed-event select-article-event target)
          completed (rf.reply/complete mapped a-reply)
          delivered (peek completed)]
      ;; The COMPLETED EVENT changed (value→article); the reply's identity facts did not.
      (is (= [:rf.work/http :article/by-id 42 1] (:work/id delivered)))
      (is (= :ok (:status delivered)))
      ;; And issuance/correlation are unaffected — `map-completed-event` stores no work-id /
      ;; status / suppression on the target (the functor law's structural guarantee):
      ;; the only difference between mapped and unmapped completion is the event payload.
      (is (= {:article {:id 42 :title "Welcome"}}
             (:value (peek (rf.reply/complete target a-reply)))))
      (is (= {:id 42 :title "Welcome"}
             (:value (peek completed)))))))

;; ---------------------------------------------------------------------------
;; Group 3 — stale suppression: the correctness boundary.
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

(deftest suppress-does-not-deliver-app-target
  (testing "suppression produces :status :stale, marks :suppressed, and does NOT deliver"
    (let [carried {:work/id [:rf.work/resource [:a/k] 4] :generation 4}
          current {:work/id [:rf.work/resource [:a/k] 5] :generation 5}
          {:keys [deliver? reply] :as out}
          (rf.reply/suppress [:article/route-replied {:slug "welcome"}] carried current
                          {:work/id      (:work/id carried)
                           :work/kind    :resource
                           :rf.frame/id  :app/main
                           :rf.reply/stale-reason :resource/generation-mismatch})]
      (is (false? deliver?) "the app reply target MUST NOT run")
      (is (= :stale (:status reply)))
      (is (true? (:stale? reply)))
      (is (= :resource/generation-mismatch (:rf.reply/stale-reason reply)))
      (is (= :suppressed (:rf.reply/work-status reply)) "ledger terminal for a stale completion")
      (is (= :suppressed (:rf.reply/work-status out)))
      (is (not (contains? reply :value)) "a stale reply carries NO value — no app-state mutation"))))

(deftest suppress-records-carried-and-current-trace-facts
  (testing "the trace facts carry BOTH the carried and current correlation"
    (let [carried {:route/nav-token "nav-1"}
          current {:route/nav-token "nav-2"}
          {:keys [trace]} (rf.reply/suppress [:x] carried current
                                          {:rf.reply/stale-reason :route/nav-token-mismatch})]
      (is (true? (:rf.reply/suppressed? trace)))
      (is (= :route/nav-token-mismatch (:rf.reply/stale-reason trace)))
      (is (= carried (:rf.reply/carried trace)))
      (is (= current (:rf.reply/current trace))))))

(deftest suppress-default-reason
  (testing "a default :rf.reply/stale-reason is supplied when the family does not name one"
    (is (= :rf.reply/correlation-mismatch
           (:rf.reply/stale-reason (:reply (rf.reply/suppress [:x] {:g 1} {:g 2})))))))

(deftest suppress-extra-cannot-override-stale-boundary
  (testing "rf2-waawic — `extra` CANNOT override the stale boundary: threading a
            natural success reply as extra still produces a valid :status :stale
            reply with NO :value (the correctness boundary is structural)"
    ;; The dangerous caller mistake the guardrail closes: passing a complete
    ;; natural-completion reply (status :ok, a :value, work-status :completed)
    ;; as `extra`. Before the fix `merge` let those win; now the stale fields
    ;; are forced and :value is stripped.
    (let [{:keys [reply deliver?]}
          (rf.reply/suppress nil {:g 1} {:g 2}
                          {:status      :ok
                           :value       {:title "should-be-stripped"}
                           :rf.reply/work-status :completed
                           :work/id     [:rf.work/http :req 1 1]
                           :work/kind   :http
                           :rf.frame/id :app/main})]
      (is (= :stale (:status reply)) "status forced to :stale, not the :ok in extra")
      (is (true? (:stale? reply)))
      (is (= :suppressed (:rf.reply/work-status reply)) "work-status forced, not :completed")
      (is (not (contains? reply :value)) "the :value in extra is stripped — a stale reply MUST NOT carry one")
      (is (false? deliver?))
      ;; identity facts from extra still ride verbatim
      (is (= [:rf.work/http :req 1 1] (:work/id reply)))
      (is (= :http (:work/kind reply)))
      (is (= :app/main (:rf.frame/id reply)))
      ;; the result validates as a conformant stale reply
      (is (rf.reply/valid-reply? reply) (str (rf.reply/validate-reply reply))))))

(deftest suppress-is-universally-non-delivering
  (testing "rf2-j538f7.14 — a stale outcome NEVER app-delivers: `suppress`
            returns :deliver? false for EVERY target — no app/data target can
            make a superseded completion deliver, and there is no per-target
            opt-in or authority to pass"
    ;; A plain short form, a descriptor, a descriptor spelling the removed
    ;; :dispatch-stale? flag (now inert data), and a descriptor forging a truthy
    ;; authority datum of any spelling — ALL are non-delivering. There is NO
    ;; app-callable issuer to obtain delivery authority; the boundary is
    ;; structural, not a check a caller can pass.
    (doseq [target [[:x]
                    {:event [:x]}
                    {:event [:app/replied] :dispatch-stale? true}
                    {:event [:app/replied] :dispatch-stale? true :re-frame.reply/stale-authority true}
                    {:event [:app/replied] :dispatch-stale? true :re-frame.reply/stale-authority :yes}
                    nil]]
      (let [{:keys [deliver? reply]} (rf.reply/suppress target {:g 1} {:g 2})]
        (is (false? deliver?)
            (str "target " (pr-str target) " must NOT deliver a stale reply to the app target"))
        (is (= :stale (:status reply)) "the outcome is still a well-formed stale reply")
        (is (not (contains? reply :value)) "a stale reply carries NO value — no app-state mutation")))))

;; ---------------------------------------------------------------------------
;; Group 3b — NO public stale-delivery issuer (rf2-j538f7.14). The former
;; `with-stale-authority` / `stale-authority?` / `StaleDeliveryCapability`
;; capability dance is DELETED: `re-frame.reply` exposes no operation that
;; creates, returns, copies, or attaches stale-delivery authority. App code that
;; directly requires the namespace cannot mint delivery for a superseded
;; completion, and a framework/tool test that wants to OBSERVE a stale reply
;; self-dispatches it on its OWN authority (nothing capability-bearing rides the
;; target).
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest no-public-stale-delivery-issuer
     (testing "rf2-j538f7.14 AC1 — the reply namespace exposes NO public
               operation that mints/attaches stale-delivery authority"
       (let [publics (set (keys (ns-publics 're-frame.reply)))]
         (doseq [sym '[with-stale-authority stale-authority?
                       ->StaleDeliveryCapability StaleDeliveryCapability
                       stale-delivery-capability]]
           (is (not (contains? publics sym))
               (str sym " must not be a public var — no app-callable "
                    "stale-delivery issuer remains")))))))

(deftest observer-self-dispatches-a-stale-reply-on-its-own-authority
  (testing "rf2-j538f7.14 AC3 — a framework/tool OBSERVER reads (:reply outcome)
            and dispatches it on its OWN authority; nothing capability-bearing
            rides the target, and the suppress outcome itself never delivers"
    (let [carried {:g 1}
          current {:g 2}
          ;; A PLAIN app-shaped target — nothing capability-bearing on it.
          target  [:app/replied]
          {:keys [deliver? reply]} (rf.reply/suppress target carried current)]
      ;; Tooth 1 — app NON-delivery: the suppress boundary is universally
      ;; non-delivering, so the ONLY way the stale reply reaches a handler is a
      ;; deliberate observer self-dispatch below.
      (is (false? deliver?) "the suppress boundary never delivers a stale reply to the app target")
      (is (= :stale (:status reply)))
      (is (rf.reply/valid-reply? reply) (str (rf.reply/validate-reply reply)))
      ;; Tooth 2 — authorised observation: the observer builds the completed
      ;; event from the stale :reply and dispatches it itself (its own trusted
      ;; path — an explicit `dispatch`, structurally separate from any target
      ;; field).
      (let [observed (atom nil)
            dispatch! (fn [ev] (reset! observed ev))]
        (dispatch! (rf.reply/complete [:tool/observed] reply))
        (is (= [:tool/observed reply] @observed)
            "the observer dispatched the stale reply on its own authority")))))

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
          summary (rf.reply/trace-summary reply {:rf.size/include-sensitive? true})]
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
    (let [summary (rf.reply/trace-summary {:status :ok :value {:secret "x"}})]
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
              #(rf.reply/normalize-target {:event :x})
              :rf.reply/invalid-target :rf.error/reply-invalid-target]
             ["non-map reply"
              #(rf.reply/validate-reply 42)
              :rf.reply/non-map-reply :rf.error/reply-non-map-reply]
             ["unknown delivery mode"
              #(rf.reply/complete {:event [:x] :delivery :weird} {:status :ok})
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
  (let [e   (catch-ex-info #(rf.reply/validate-reply 42))
        msg (ex-message e)]
    (is (some? e))
    (is (not (rf.error/keyword-only-message? msg))
        "the message is a human sentence, never a bare keyword (Spec 009 rule 1)")
    (is (rf.error/message-has-id-token? msg)
        "the message carries the [:rf.error/<id>] greppability token (rule 4)")
    (is (string? (:reason (ex-data e)))
        ":reason is the required human sentence")))

;; ---------------------------------------------------------------------------
;; rf2-70h9wn — `walk-find-host-handle-bounded`, the budget-bounded sibling
;; the dev-only event-payload serialisability lint reuses. Same detection +
;; walk as `walk-find-host-handle`, but stops early (returns nil — a false
;; negative, the safe direction for an advisory surface) once the node budget
;; is exhausted.
;; ---------------------------------------------------------------------------

(deftest bounded-walk-finds-a-host-handle-within-budget
  (testing "a fn nested a couple of levels deep is found well within a
   generous budget"
    (is (= [:a :b]
           (rf.reply/walk-find-host-handle-bounded
             {:a {:b (fn [] nil)}} 500)))))

(deftest bounded-walk-clean-payload-returns-nil
  (testing "an all-EDN payload never reports a handle, regardless of budget"
    (is (nil? (rf.reply/walk-find-host-handle-bounded
                {:a [1 2 {:b #{:x :y}}]} 500)))))

(deftest bounded-walk-gives-up-once-budget-exhausted
  (testing "a budget too small to reach the handle returns nil (false
   negative — the fail-safe direction for an ADVISORY lint) rather than
   throwing or over-running"
    (is (nil? (rf.reply/walk-find-host-handle-bounded
                {:a {:b {:c (fn [] nil)}}} 1))
        "the handle is 3 levels deep; a 1-node budget gives up first")))
