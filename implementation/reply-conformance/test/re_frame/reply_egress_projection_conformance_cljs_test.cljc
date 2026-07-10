(ns re-frame.reply-egress-projection-conformance-cljs-test
  "Reply-envelope egress-projection conformance.

  The vocabulary matrix checks raw, data-only reply envelopes. This suite
  independently checks the shared projection primitives used by trace and
  record-level egress. It does not claim coverage of every tool or log
  consumer; those consumers remain responsible for calling these boundaries.

  Reply `:value`, `:error`, `:correlation`, and `:meta` slots may carry
  family-specific data, including fields classified as sensitive or large.
  The tests establish that:

    1. `trace-summary` projects each wire-bearing slot through
       `elide-wire-value`, while framework identity facts remain unchanged;
    2. sensitive classifications and large-value elision compose, with
       sensitive winning at a both-marked path;
    3. an explicit frame selects the policy; otherwise a carried
       `:rf.frame/id` self-summarizes, and unresolved frames fail closed;
    4. off-box and local-redacted profiles protect classified fields, while
       the explicit `:rf.egress/local-raw` profile exposes them;
    5. durable projection strips a mapped target's ephemeral function, and a
       raw completed event can be explicitly summarized before egress.

  This is pure-function and live-frame conformance over `re-frame.reply`
  (`trace-summary`, `complete`, `map-completed-event`, `durable-target`) and
  `re-frame.core/project-egress`. Reply-target functor laws remain owned by core and the
  timer probe; mapping is used here only to distinguish ephemeral and durable
  target representations. The `.cljc` namespace runs in both the CLJS node
  gate and the JVM test alias.

  Canonical contract: `spec/015-Data-Classification.md` §`project-egress`
  + `spec/Managed-Effects.md` §Tracing (the data-only trace summary)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.reply :as reply]
            [re-frame.reply-conformance-fixtures :as fixtures]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The frame's elision registry classifies paths within each reply wire slot.
;;
;;   [:token]      → sensitive
;;   [:blob]       → large
;;   [:secret-big] → sensitive and large
;; ---------------------------------------------------------------------------

(def ^:private frame-id :reply-egress/main)

(def ^:private big-string
  ;; Exceeds the default size threshold as well as carrying a declaration.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! []
  (rf/reg-frame frame-id {})
  ;; Seed the same runtime registry that classification effects update.
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:token] [:secret-big]]
                :large     [[:blob] [:secret-big]]}))))

;; Payload fixtures use the classified coordinates directly.
(defn- reply-body []
  {:token      "bearer-SECRET-do-not-ship"
   :blob       big-string
   :secret-big big-string
   :public     {:count 3}})

(def ^:private work-id
  [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1])

(def ^:private completed-at-ms fixtures/completion-time-ms)

(defn- ok-reply []
  ;; A complete canonical envelope keeps the projection assertions realistic.
  {:status               :ok
   :value                (reply-body)
   :rf.reply/work-id     work-id
   :rf.reply/work-kind   :resource
   :rf.reply/work-status :completed
   :rf.frame/id          frame-id
   :completed-at         completed-at-ms})

(defn- error-reply []
  ;; The failure payload reuses the same classified paths as the success value.
  {:status      :error
   :error       {:kind   :rf.http/http-5xx
                 :status 503
                 :token  "bearer-SECRET-do-not-ship"
                 :blob   big-string}
   :rf.reply/work-id     work-id
   :rf.reply/work-kind   :resource
   :rf.reply/work-status :failed
   :rf.frame/id frame-id})

(defn- redacted? [v] (= privacy/redacted-sentinel v))
(defn- large-marker? [v] (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; trace-summary projects every wire-bearing slot and preserves identity facts.
;; ---------------------------------------------------------------------------

(deftest trace-summary-projects-wire-slots-through-the-shared-elider
  (testing "trace-summary projects each wire slot under the explicit frame"
    (mk-frame!)
    (let [reply   (assoc (ok-reply)
                         :correlation {:token "corr-SECRET"}
                         :meta        {:blob big-string})
          summary (reply/trace-summary reply {:frame frame-id})]
      (testing "the sensitive reply-value leaf is redacted"
        (is (redacted? (get-in summary [:value :token]))
            ":value sensitive leaf redacted"))
      (testing "the large reply-value leaf is elided"
        (is (large-marker? (get-in summary [:value :blob]))
            ":value large leaf elided to a marker"))
      (testing "the unmarked sibling rides through"
        (is (= 3 (get-in summary [:value :public :count]))))
      (testing ":correlation and :meta are also projected"
        (is (redacted? (get-in summary [:correlation :token]))
            ":correlation sensitive leaf redacted")
        (is (large-marker? (get-in summary [:meta :blob]))
            ":meta large leaf elided"))
      (testing "framework identity facts remain unchanged"
        (is (= work-id (:rf.reply/work-id summary))         ":rf.reply/work-id verbatim")
        (is (= :ok (:status summary))              ":status verbatim")
        (is (= :resource (:rf.reply/work-kind summary))     ":rf.reply/work-kind verbatim")
        (is (= :completed (:rf.reply/work-status summary))  ":rf.reply/work-status verbatim")
        (is (= frame-id (:rf.frame/id summary))    ":rf.frame/id verbatim")
        (is (= completed-at-ms (:completed-at summary)) ":completed-at verbatim")))))

(deftest trace-summary-projects-the-error-failure-payload
  (testing "the :error payload is projected like the :value payload"
    (mk-frame!)
    (let [summary (reply/trace-summary (error-reply) {:frame frame-id})]
      (is (redacted? (get-in summary [:error :token]))
          "a sensitive leaf inside the :error response body is redacted")
      (is (large-marker? (get-in summary [:error :blob]))
          "a large leaf inside the :error response body is elided")
      ;; Unclassified leaves inside the wire slot remain visible.
      (is (= :rf.http/http-5xx (get-in summary [:error :kind]))
          "the family error :kind rides (not a declared wire path)")
      (is (= 503 (get-in summary [:error :status]))
          "the family error :status rides (not a declared wire path)")
      (is (= work-id (:rf.reply/work-id summary)) ":rf.reply/work-id verbatim on an error reply"))))

;; ---------------------------------------------------------------------------
;; Sensitive classification wins over large classification.
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large-at-a-both-marked-reply-slot
  (testing "a both-marked reply value redacts rather than large-elides"
    (mk-frame!)
    (let [summary (reply/trace-summary (ok-reply) {:frame frame-id})]
      (is (redacted? (get-in summary [:value :secret-big]))
          "the both-marked leaf is REDACTED, not large-elided")
      (is (not (large-marker? (get-in summary [:value :secret-big])))
          "sensitive wins — no large marker at the both-marked path"))))

;; ---------------------------------------------------------------------------
;; An explicit frame takes precedence; otherwise the carried frame is used.
;; An unresolved selected frame fails closed.
;; ---------------------------------------------------------------------------

(deftest egress-frame-comes-from-the-explicit-opt-and-fails-closed-when-unresolved
  (testing "an explicit live frame applies policy and an unresolved frame fails closed"
    (mk-frame!)
    ;; A live explicit frame applies its classification policy.
    (let [known (reply/trace-summary (ok-reply) {:frame frame-id})]
      (is (redacted? (get-in known [:value :token]))
          "a KNOWN :frame opt applies its sensitive policy"))
    ;; A never-registered frame cannot supply policy, so the whole slot redacts.
    (let [unresolved (reply/trace-summary (ok-reply) {:frame :reply-egress/ghost})]
      (is (redacted? (:value unresolved))
          "an UNRESOLVED :frame opt fails closed — the whole :value slot redacts")
      (is (not= "bearer-SECRET-do-not-ship" (get-in unresolved [:value :token]))
          "the raw token NEVER ships under an unresolved frame")
      ;; Identity facts are not wire slots and remain unchanged.
      (is (= work-id (:rf.reply/work-id unresolved))
          ":rf.reply/work-id still rides verbatim — only the wire slots fail closed"))))

;; Without an explicit option, trace-summary resolves policy from the carried
;; frame stamp even when there is no ambient frame scope.
(deftest carried-frame-stamp-auto-resolves-into-the-egress-policy
  (mk-frame!)
  ;; Remove ambient scope so the carried stamp is the only frame source.
  (binding [frame/*current-frame* nil]
    (testing "a live carried frame supplies the wire-slot policy"
      (let [reply   (ok-reply)
            summary (reply/trace-summary reply nil)]
        (is (= frame-id (:rf.frame/id reply))
            "precondition: reply carries the live frame stamp")
        (is (redacted? (get-in summary [:value :token]))
            "the carried frame's sensitive policy redacts [:token]")
        (is (large-marker? (get-in summary [:value :blob]))
            "the carried frame's large policy elides [:blob]")
        (is (= 3 (get-in summary [:value :public :count]))
            "the unmarked sibling passes through — per-leaf policy, not a whole-slot redact")
        (is (not= "bearer-SECRET-do-not-ship" (get-in summary [:value :token]))
            "the raw token NEVER ships")
        (is (= frame-id (:rf.frame/id summary))
            "the carried :rf.frame/id remains an identity fact")
        (is (= work-id (:rf.reply/work-id summary))
            ":rf.reply/work-id still rides verbatim")))
    (testing "an unresolved carried frame fails closed"
      (let [reply   (assoc (ok-reply) :rf.frame/id :reply-egress/ghost)
            summary (reply/trace-summary reply nil)]
        (is (redacted? (:value summary))
            "an unresolved carried stamp still fails closed under nil opts")
        (is (= :reply-egress/ghost (:rf.frame/id summary))
            "the carried (unresolved) :rf.frame/id rides verbatim as identity")))
    (testing "an explicit frame takes precedence over the carried stamp"
      (let [reply   (ok-reply)
            summary (reply/trace-summary reply {:frame :reply-egress/ghost})]
        (is (redacted? (:value summary))
            "the explicit unresolved frame wins and fails closed")))))

;; ---------------------------------------------------------------------------
;; Record-level egress protects classified fields except under local-raw.
;; ---------------------------------------------------------------------------

(deftest off-box-project-egress-redacts-the-reply-body-and-local-raw-exposes-it
  (testing "project-egress protects classified fields unless local-raw is explicit"
    (mk-frame!)
    (doseq [p [:rf.egress/off-box-observability
               :rf.egress/off-box-tool
               :rf.egress/local-redacted]]
      (let [out (rf/project-egress (reply-body)
                  {:frame frame-id :rf.egress/profile p})]
        (is (redacted? (get-in out [:token]))
            (str p ": the sensitive reply-body leaf is redacted"))
        (is (large-marker? (get-in out [:blob]))
            (str p ": the large reply-body leaf is elided"))
        (is (= 3 (get-in out [:public :count]))
            (str p ": the unmarked sibling passes through"))))
    (testing ":rf.egress/local-raw exposes classified fields"
      (let [out (rf/project-egress (reply-body)
                  {:frame frame-id :rf.egress/profile :rf.egress/local-raw})]
        (is (= "bearer-SECRET-do-not-ship" (get-in out [:token]))
            "local-raw keeps the token")
        (is (= big-string (get-in out [:blob]))
            "local-raw keeps the large field")))))

(deftest off-box-project-egress-fails-closed-on-an-unresolved-frame
  (testing "project-egress fails closed for an unresolved explicit frame"
    (let [out (rf/project-egress (reply-body)
                {:frame :reply-egress/ghost :rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? out)
          "an unresolved-frame off-box egress conservatively redacts the whole reply body")
      (is (not= "bearer-SECRET-do-not-ship" (get-in out [:token]))
          "the raw token NEVER ships under an unresolved frame"))))

;; ---------------------------------------------------------------------------
;; Mapped targets have distinct ephemeral and durable representations; completed
;; events remain raw in memory and require projection before egress.
;; ---------------------------------------------------------------------------

(deftest a-mapped-reply-target-has-a-data-only-durable-representation
  (testing "durable-target strips a mapped target's ephemeral accumulator"
    (let [relayed (reply/map-completed-event (fn [event] [:parent/relay event])
                                    [:article/loaded {:id 42}])]
      (is (false? (reply/data-only-target? relayed))
          "a mapped target is not data-only while it carries ::post")
      (let [durable (reply/durable-target relayed)]
        (is (true? (reply/data-only-target? durable))
            "the durable projection strips the accumulator")))))

(deftest a-completed-reply-event-can-be-summarized-for-egress
  (testing "a completed event is raw in memory and its reply can be summarized for egress"
    (mk-frame!)
    (let [target    [:article/loaded {:id 42}]
          completed (reply/complete target (ok-reply))
          ;; Completion appends the raw reply; trace-summary creates the egress view.
          delivered (peek completed)
          summary   (reply/trace-summary delivered {:frame frame-id})]
      (is (= [:article/loaded {:id 42} (ok-reply)] completed)
          "the completed event carries the raw reply as its final arg in-memory")
      (is (redacted? (get-in summary [:value :token]))
          "the summary redacts the sensitive body")
      (is (large-marker? (get-in summary [:value :blob]))
          "the egress projection elides the large body")
      (is (= work-id (:rf.reply/work-id summary))
          "identity facts remain on the summary"))))

;; ---------------------------------------------------------------------------
;; Control: the raw reply contains the protected fields and the shared walker
;; removes them, so the projection assertions are not satisfied by the fixture.
;; ---------------------------------------------------------------------------

(deftest control-the-raw-reply-carries-the-sensitive-body-before-projection
  (testing "the raw fixture contains protected fields before projection"
    (mk-frame!)
    (let [reply (ok-reply)]
      (is (= "bearer-SECRET-do-not-ship" (get-in reply [:value :token]))
          "the RAW reply carries the sensitive token (pre-projection)")
      (is (= big-string (get-in reply [:value :blob]))
          "the RAW reply carries the large blob (pre-projection)")
      ;; The direct walker provides the expected projection result.
      (let [direct (elision/elide-wire-value (:value reply) {:frame frame-id})]
        (is (redacted? (get-in direct [:token]))
            "the shared walker redacts the token")))))
