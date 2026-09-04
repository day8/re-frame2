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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.reply :as rf.reply]
            [re-frame.reply-conformance-fixtures :as rf.reply-conformance-fixtures]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The frame's elision registry classifies paths within each reply wire slot.
;;
;;   [:token]      → sensitive
;;   [:blob]       → large
;;   [:secret-big] → sensitive and large
;; ---------------------------------------------------------------------------

(def ^:private frame-id :reply-egress/main)

;; The raw sensitive token — the exact string that MUST NOT survive projection
;; into any off-box / redacted wire slot. Named once so the sentinel predicate
;; and the fixtures share a single source of truth.
(def ^:private raw-token "bearer-SECRET-do-not-ship")

(def ^:private big-string
  ;; Exceeds the default size threshold as well as carrying a declaration.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! []
  (rf/make-frame {:id frame-id})
  ;; Seed the same runtime registry that classification effects update.
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:token] [:secret-big]]
                :large     [[:blob] [:secret-big]]}))))

;; Payload fixtures use the classified coordinates directly.
(defn- reply-body []
  {:token      raw-token
   :blob       big-string
   :secret-big big-string
   :public     {:count 3}})

(def ^:private work-id
  [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1])

(def ^:private completed-at-ms rf.reply-conformance-fixtures/completion-time-ms)

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
                 :token  raw-token
                 :blob   big-string}
   :rf.reply/work-id     work-id
   :rf.reply/work-kind   :resource
   :rf.reply/work-status :failed
   :rf.frame/id frame-id})

(defn- redacted? [value] (= rf.privacy/redacted-sentinel value))
(defn- large-marker? [value] (and (map? value) (contains? value :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; Recursive raw-value-absence predicate (Finding 2, rf2-3fc89f.7).
;;
;; `large-marker?` proves a marker is PRESENT; it does NOT prove the raw value
;; LEFT — a leaking marker representation or a reply-slot projection regression
;; could carry `{:rf.size/large-elided true :raw <40k-string>}` and still pass a
;; marker-presence check. This portable tree walk (no JVM-only walk API — it
;; runs identically on both hosts) asserts the exact raw sentinel is absent from
;; EVERY nested node of a projected slot, closing that fail-open gap.
;; ---------------------------------------------------------------------------

(defn- tree-contains?
  "True iff any node reachable in `data` satisfies `pred`. Recurses map keys AND
  vals, sequential collections, and sets; applies `pred` at every node."
  [pred data]
  (cond
    (pred data)  true
    (map? data)  (boolean (some (fn [[map-key map-value]] (or (tree-contains? pred map-key)
                                                               (tree-contains? pred map-value)))
                                data))
    (coll? data) (boolean (some #(tree-contains? pred %) data))
    :else        false))

(defn- embeds-raw-token?
  "True iff the raw sensitive token survives anywhere in `data` — as a string
  equal to OR embedding the original bearer token (a leaking representation
  might wrap rather than replace it)."
  [data]
  (tree-contains? #(and (string? %) (str/includes? % raw-token)) data))

(defn- embeds-raw-blob?
  "True iff the raw 40k big-string survives anywhere in `data` (equal or
  embedded). The legitimate `:rf.size/large-elided` marker carries only a
  byte-count / type / handle, so it never trips this predicate."
  [data]
  (tree-contains? #(and (string? %) (str/includes? % big-string)) data))

;; ---------------------------------------------------------------------------
;; trace-summary projects every wire-bearing slot and preserves identity facts.
;; ---------------------------------------------------------------------------

(deftest trace-summary-projects-wire-slots-through-the-shared-elider
  (testing "trace-summary projects each wire slot under the explicit frame"
    (mk-frame!)
    (let [reply   (assoc (ok-reply)
                         :correlation {:token "corr-SECRET"}
                         :meta        {:blob big-string})
          summary (rf.reply/trace-summary reply {:frame frame-id})]
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
        (is (= completed-at-ms (:completed-at summary)) ":completed-at verbatim"))
      (testing "NO raw sensitive/large value survives in ANY projected wire slot"
        ;; Marker/redaction PRESENCE is asserted above; this proves the raw
        ;; value is ABSENT — recursively, so a value retained under a sibling
        ;; key or embedded in a marker cannot slip through.
        (is (not (embeds-raw-token? (:value summary)))
            "the raw token is absent from the projected :value slot")
        (is (not (embeds-raw-blob? (:value summary)))
            "the raw 40k blob is absent from the projected :value slot")
        (is (not (embeds-raw-token? (:correlation summary)))
            "the raw token is absent from the projected :correlation slot")
        (is (not (embeds-raw-blob? (:meta summary)))
            "the raw blob is absent from the projected :meta slot")
        ;; And nothing raw survives anywhere in the whole summary (identity
        ;; facts carry no wire values).
        (is (not (embeds-raw-token? summary))
            "no raw token anywhere in the trace summary")
        (is (not (embeds-raw-blob? summary))
            "no raw blob anywhere in the trace summary")))))

(deftest trace-summary-projects-the-error-failure-payload
  (testing "the :error payload is projected like the :value payload"
    (mk-frame!)
    (let [summary (rf.reply/trace-summary (error-reply) {:frame frame-id})]
      (is (redacted? (get-in summary [:error :token]))
          "a sensitive leaf inside the :error response body is redacted")
      (is (large-marker? (get-in summary [:error :blob]))
          "a large leaf inside the :error response body is elided")
      ;; Unclassified leaves inside the wire slot remain visible.
      (is (= :rf.http/http-5xx (get-in summary [:error :kind]))
          "the family error :kind rides (not a declared wire path)")
      (is (= 503 (get-in summary [:error :status]))
          "the family error :status rides (not a declared wire path)")
      (is (= work-id (:rf.reply/work-id summary)) ":rf.reply/work-id verbatim on an error reply")
      (testing "NO raw sensitive/large value survives in the projected :error slot"
        (is (not (embeds-raw-token? (:error summary)))
            "the raw token is absent from the projected :error payload")
        (is (not (embeds-raw-blob? (:error summary)))
            "the raw blob is absent from the projected :error payload")
        (is (not (embeds-raw-token? summary))
            "no raw token anywhere in the error trace summary")))))

;; ---------------------------------------------------------------------------
;; Sensitive classification wins over large classification.
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large-at-a-both-marked-reply-slot
  (testing "a both-marked reply value redacts rather than large-elides"
    (mk-frame!)
    (let [summary (rf.reply/trace-summary (ok-reply) {:frame frame-id})]
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
    (let [known (rf.reply/trace-summary (ok-reply) {:frame frame-id})]
      (is (redacted? (get-in known [:value :token]))
          "a KNOWN :frame opt applies its sensitive policy"))
    ;; A never-registered frame cannot supply policy, so the whole slot redacts.
    (let [unresolved (rf.reply/trace-summary (ok-reply) {:frame :reply-egress/ghost})]
      (is (redacted? (:value unresolved))
          "an UNRESOLVED :frame opt fails closed — the whole :value slot redacts")
      (is (not (embeds-raw-token? unresolved))
          "the raw token NEVER ships under an unresolved frame")
      ;; Identity facts are not wire slots and remain unchanged.
      (is (= work-id (:rf.reply/work-id unresolved))
          ":rf.reply/work-id still rides verbatim — only the wire slots fail closed"))))

;; Without an explicit option, trace-summary resolves policy from the carried
;; frame stamp even when there is no ambient frame scope.
(deftest carried-frame-stamp-auto-resolves-into-the-egress-policy
  (mk-frame!)
  ;; Remove ambient scope so the carried stamp is the only frame source.
  (binding [rf.frame/*current-frame* nil]
    (testing "a live carried frame supplies the wire-slot policy"
      (let [reply   (ok-reply)
            summary (rf.reply/trace-summary reply nil)]
        (is (= frame-id (:rf.frame/id reply))
            "precondition: reply carries the live frame stamp")
        (is (redacted? (get-in summary [:value :token]))
            "the carried frame's sensitive policy redacts [:token]")
        (is (large-marker? (get-in summary [:value :blob]))
            "the carried frame's large policy elides [:blob]")
        (is (= 3 (get-in summary [:value :public :count]))
            "the unmarked sibling passes through — per-leaf policy, not a whole-slot redact")
        (is (not (embeds-raw-token? summary))
            "the raw token NEVER ships")
        (is (= frame-id (:rf.frame/id summary))
            "the carried :rf.frame/id remains an identity fact")
        (is (= work-id (:rf.reply/work-id summary))
            ":rf.reply/work-id still rides verbatim")))
    (testing "an unresolved carried frame fails closed"
      (let [reply   (assoc (ok-reply) :rf.frame/id :reply-egress/ghost)
            summary (rf.reply/trace-summary reply nil)]
        (is (redacted? (:value summary))
            "an unresolved carried stamp still fails closed under nil opts")
        (is (= :reply-egress/ghost (:rf.frame/id summary))
            "the carried (unresolved) :rf.frame/id rides verbatim as identity")))
    (testing "an explicit frame takes precedence over the carried stamp"
      (let [reply   (ok-reply)
            summary (rf.reply/trace-summary reply {:frame :reply-egress/ghost})]
        (is (redacted? (:value summary))
            "the explicit unresolved frame wins and fails closed")))))

;; ---------------------------------------------------------------------------
;; Record-level egress protects classified fields except under local-raw.
;; ---------------------------------------------------------------------------

(deftest off-box-project-egress-redacts-the-reply-body-and-local-raw-exposes-it
  (testing "project-egress protects classified fields unless local-raw is explicit"
    (mk-frame!)
    (doseq [profile [:rf.egress/off-box-observability
                     :rf.egress/off-box-tool
                     :rf.egress/local-redacted]]
      (let [projected-result (rf/project-egress (reply-body)
                              {:frame frame-id :rf.egress/profile profile})]
        (is (redacted? (get-in projected-result [:token]))
            (str profile ": the sensitive reply-body leaf is redacted"))
        (is (large-marker? (get-in projected-result [:blob]))
            (str profile ": the large reply-body leaf is elided"))
        (is (= 3 (get-in projected-result [:public :count]))
            (str profile ": the unmarked sibling passes through"))
        ;; Marker/redaction presence is asserted above; prove raw ABSENCE too —
        ;; recursively, across the whole projected record.
        (is (not (embeds-raw-token? projected-result))
            (str profile ": the raw token does NOT survive project-egress anywhere"))
        (is (not (embeds-raw-blob? projected-result))
            (str profile ": the raw blob does NOT survive project-egress anywhere"))))
    (testing ":rf.egress/local-raw exposes classified fields"
      (let [projected-result (rf/project-egress (reply-body)
                              {:frame frame-id :rf.egress/profile :rf.egress/local-raw})]
        (is (= raw-token (get-in projected-result [:token]))
            "local-raw keeps the token")
        (is (= big-string (get-in projected-result [:blob]))
            "local-raw keeps the large field")
        ;; The explicit positive control: the SAME sentinel predicate that must
        ;; find nothing off-box MUST find the originals under local-raw.
        (is (embeds-raw-token? projected-result)
            "local-raw is the positive control — the raw token IS present")
        (is (embeds-raw-blob? projected-result)
            "local-raw is the positive control — the raw blob IS present")))))

(deftest off-box-project-egress-fails-closed-on-an-unresolved-frame
  (testing "project-egress fails closed for an unresolved explicit frame"
    (let [projected-result (rf/project-egress (reply-body)
                            {:frame :reply-egress/ghost :rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? projected-result)
          "an unresolved-frame off-box egress conservatively redacts the whole reply body")
      (is (not (embeds-raw-token? projected-result))
          "the raw token NEVER ships under an unresolved frame")
      (is (not (embeds-raw-blob? projected-result))
          "the raw blob NEVER ships under an unresolved frame"))))

;; ---------------------------------------------------------------------------
;; Mapped targets have distinct ephemeral and durable representations; completed
;; events remain raw in memory and require projection before egress.
;; ---------------------------------------------------------------------------

(deftest a-mapped-reply-target-has-a-data-only-durable-representation
  (testing "durable-target strips a mapped target's ephemeral accumulator"
    (let [relayed (rf.reply/map-completed-event (fn [event] [:parent/relay event])
                                    [:article/loaded {:id 42}])]
      (is (false? (rf.reply/data-only-target? relayed))
          "a mapped target is not data-only while it carries ::post")
      (let [durable (rf.reply/durable-target relayed)]
        (is (true? (rf.reply/data-only-target? durable))
            "the durable projection strips the accumulator")))))

(deftest a-completed-reply-event-can-be-summarized-for-egress
  (testing "a completed event is raw in memory and its reply can be summarized for egress"
    (mk-frame!)
    (let [target    [:article/loaded {:id 42}]
          completed (rf.reply/complete target (ok-reply))
          ;; Completion appends the raw reply; trace-summary creates the egress view.
          delivered (peek completed)
          summary   (rf.reply/trace-summary delivered {:frame frame-id})]
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
      (is (= raw-token (get-in reply [:value :token]))
          "the RAW reply carries the sensitive token (pre-projection)")
      (is (= big-string (get-in reply [:value :blob]))
          "the RAW reply carries the large blob (pre-projection)")
      ;; The sentinel predicate finds the originals in the raw reply — its
      ;; positive baseline (the redacted/projected outputs above must NOT).
      (is (embeds-raw-token? reply)
          "the sentinel predicate finds the raw token in the pre-projection reply")
      (is (embeds-raw-blob? reply)
          "the sentinel predicate finds the raw blob in the pre-projection reply")
      ;; The direct walker provides the expected projection result.
      (let [direct (rf.elision/elide-wire-value (:value reply) {:frame frame-id})]
        (is (redacted? (get-in direct [:token]))
            "the shared walker redacts the token")))))

;; ---------------------------------------------------------------------------
;; Adversarial control (Finding 2): a marker map that STILL embeds the raw
;; large value passes the superficial `large-marker?` presence check but is
;; caught by the recursive sentinel predicate — proving the raw-value-absence
;; gate has teeth that marker-presence alone lacks.
;; ---------------------------------------------------------------------------

(deftest sentinel-predicate-catches-a-marker-that-embeds-the-raw-value
  (testing "a large-marker map that ALSO retains the raw big-string fools
            large-marker? but the recursive sentinel predicate would go RED on it"
    (let [leaking {:rf.size/large-elided true :raw big-string}]
      ;; The fail-open gap: the superficial marker-presence check is satisfied.
      (is (large-marker? leaking)
          "large-marker? returns true for the leaking marker (the gap Finding 2 closes)")
      ;; The recursive sentinel predicate catches the embedded raw value, so a
      ;; raw-value-absence assertion built on it FAILS on this leaking shape.
      (is (embeds-raw-blob? leaking)
          "the sentinel predicate FINDS the raw blob the leaking marker embeds")))
  (testing "a LEGITIMATE large-elided marker carries no raw value, so the
            sentinel predicate does not false-positive on it"
    (let [clean (rf.elision/->marker big-string [:blob] {})]
      (is (large-marker? clean) "a real marker is still a marker")
      (is (not (embeds-raw-blob? clean))
          "the real marker carries only a byte-count / type / handle — no raw blob"))))
