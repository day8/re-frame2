(ns re-frame.reply-egress-projection-conformance-cljs-test
  "EP-0015 reply-envelope EGRESS-projection conformance (rf2-p41683).

  ## The gap this closes

  The sibling reply-conformance tiers prove the EP-0011 RAW reply
  vocabulary / functor laws: the cross-family table asserts raw `:value` /
  `:error` envelope slots + the data-only / no-host-handle invariants
  (`reply-vocab-conformance`), and the functor suite proves relocated
  completions preserve the raw appended reply (`reply-functor-law`). NONE of
  them exercises the EP-0015 EGRESS boundary for reply envelopes — none
  calls `re-frame.reply/trace-summary` or `re-frame.core/project-egress`
  under an off-box profile.

  Why it matters: Spec 015 + Managed-Effects require EVERY managed-effect
  trace / tool / log boundary to project wire-bearing reply slots through
  the SHARED elider. Reply `:value` / `:error` / `:correlation` / `:meta`
  can carry owner-local HTTP / resource / mutation / machine / route data,
  including schema-marked `:sensitive?` / `:large?` response bodies and
  failure payloads. Without a reply-conformance guard a family can keep
  passing the raw envelope-shape tests while drifting to raw owner-local
  data at trace / tool / off-box boundaries.

  ## What this tier proves (the bead's enumerated legs)

    1. wire-bearing reply slots are projected by the SHARED elider before
       trace / tool / log egress — `trace-summary` routes `:value` / `:error`
       / `:correlation` / `:meta` through `elide-wire-value`, and
       `project-egress` routes a reply tree through the same walker; identity
       facts (`:work/id` / `:status` / `:rf.frame/id` / timestamps) ride
       VERBATIM;
    2. sensitive + large markings are honored, with SENSITIVE winning over
       large at a both-marked path;
    3. the egress frame resolves from the explicit `{:frame …}` opt when
       given, otherwise from the reply map's OWN carried `:rf.frame/id`
       stamp — a carried reply SELF-SUMMARIZES under nil opts (rf2-wjo28z);
       explicit `:frame` still wins, and a frame that resolves to nothing
       LIVE (neither explicit nor carried) FAILS CLOSED (the whole value
       redacts — NOT a fall-through to `:rf/default` or a raw pass-through).
       The carried `:rf.frame/id` also rides verbatim as an identity fact;
    4. off-box profiles do NOT expose raw response bodies / failure payloads
       unless a classified projection explicitly permits it (`:local-raw`);
    5. relocated / completed reply targets do not create an UNPROJECTED
       durable / tool / log representation of the raw reply envelope — the
       relocated target's durable projection strips the ephemeral accumulator
       and the completed reply egress projects the wire slots.

  Pure-fn + live-frame conformance over `re-frame.reply` (`trace-summary`,
  `complete`, `map-completed-event`, `durable-target`) and `re-frame.core`
  (`project-egress`) against a frame whose elision registry classifies reply
  value / error sub-paths. Lives in the cross-artefact `reply-conformance/`
  surface alongside the vocab + functor tiers.

  `.cljc`, dual-runtime: the shadow-cljs `:node-test` build
  (`npm run test:cljs`, ns matches `cljs-test$`) AND the JVM
  `clojure -M:test` runner both pick it up.

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
;; The frame whose elision registry classifies reply wire sub-paths. The
;; declarations are app-db PATHS; the egress walk is SEEDED with the matching
;; `:path` offset so the reply slot's internal coordinates align with the
;; declaration (the same seed-path mechanism `project-egress` uses for a
;; direct-read value at a `:path`).
;;
;;   [:token]      → SENSITIVE  (a bearer token in a response body)
;;   [:blob]       → LARGE      (a large response body)
;;   [:secret-big] → SENSITIVE *and* LARGE (the both-marked path — sensitive
;;                                must WIN)
;; ---------------------------------------------------------------------------

(def ^:private frame-id :reply-egress/main)

(def ^:private big-string
  ;; > the 16384-byte default threshold so an UNDECLARED large would auto-detect
  ;; too; here [:blob] is DECLARED large so the marker is deterministic.
  (apply str (repeat 40000 \x)))

(defn- mk-frame! []
  (rf/reg-frame frame-id {})
  ;; EP-0025: durable app-db classification rides the commit-plane effect
  ;; path (:source :effect) — no longer a frame annotation. The egress walker
  ;; enforces sensitive-wins-over-large, so [:secret-big] (declared both)
  ;; still redacts.
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:token] [:secret-big]]
                :large     [[:blob] [:secret-big]]}))))

;; A reply `:value` body whose leaves sit at the declared coordinates (seeded
;; via `:path []`, so `[:token]` / `[:blob]` / `[:secret-big]` are the walk
;; coordinates).
(defn- reply-body []
  {:token      "bearer-SECRET-do-not-ship"
   :blob       big-string
   :secret-big big-string
   :public     {:count 3}})

(def ^:private work-id
  [:rf.work/resource [:rf.scope/global :article/by-id {:id 42}] 1])

;; Shared across the reply-conformance tier — owned by
;; `re-frame.reply-conformance-fixtures` (rf2-b2a3a2).
(def ^:private completed-at-ms fixtures/completion-time-ms)

(defn- ok-reply []
  ;; The canonical :ok reply SHAPE built via the shared
  ;; `re-frame.reply-conformance-fixtures/canonical-ok-reply` (rf2-b2a3a2).
  ;; This suite's row additionally pins `:rf.reply/work-status :completed`.
  (fixtures/canonical-ok-reply
    {:value        (reply-body)
     :work/id      work-id
     :work/kind    :resource
     :rf.reply/work-status  :completed
     :rf.frame/id  frame-id
     :completed-at completed-at-ms}))

(defn- error-reply []
  ;; The failure payload's wire-bearing leaves sit at the declared coordinates
  ;; (`[:token]` sensitive, `[:blob]` large) — seeded via `:path []`, the same
  ;; alignment the success `:value` body uses — so the shared elider classifies
  ;; them. The structural family facts (`:kind` / `:status`) are NOT at a
  ;; declared path and ride verbatim.
  {:status      :error
   :error       {:kind   :rf.http/http-5xx
                 :status 503
                 :token  "bearer-SECRET-do-not-ship"
                 :blob   big-string}
   :work/id     work-id
   :work/kind   :resource
   :rf.reply/work-status :failed
   :rf.frame/id frame-id})

(defn- redacted? [v] (= privacy/redacted-sentinel v))
(defn- large-marker? [v] (and (map? v) (contains? v :rf.size/large-elided)))

;; ---------------------------------------------------------------------------
;; (1) trace-summary routes EVERY wire-bearing slot through the SHARED elider;
;;     identity facts ride verbatim.
;; ---------------------------------------------------------------------------

(deftest trace-summary-projects-wire-slots-through-the-shared-elider
  (testing "Managed-Effects §Tracing — trace-summary elides :value / :error /
            :correlation / :meta through the SHARED elide-wire-value walker
            (frame from the explicit :frame opt), keeping identity facts verbatim"
    (mk-frame!)
    (let [reply   (assoc (ok-reply)
                         :correlation {:token "corr-SECRET"}
                         :meta        {:blob big-string})
          summary (reply/trace-summary reply {:frame frame-id})]
      (testing "the SENSITIVE reply-value leaf is redacted by the shared elider"
        (is (redacted? (get-in summary [:value :token]))
            ":value sensitive leaf redacted"))
      (testing "the LARGE reply-value leaf is elided to the shared marker"
        (is (large-marker? (get-in summary [:value :blob]))
            ":value large leaf elided to a marker"))
      (testing "the unmarked sibling rides through"
        (is (= 3 (get-in summary [:value :public :count]))))
      (testing "the :correlation + :meta wire slots are ALSO projected"
        (is (redacted? (get-in summary [:correlation :token]))
            ":correlation sensitive leaf redacted")
        (is (large-marker? (get-in summary [:meta :blob]))
            ":meta large leaf elided"))
      (testing "identity / correlation facts ride VERBATIM (never elided)"
        (is (= work-id (:work/id summary))         ":work/id verbatim")
        (is (= :ok (:status summary))              ":status verbatim")
        (is (= :resource (:work/kind summary))     ":work/kind verbatim")
        (is (= :completed (:rf.reply/work-status summary))  ":rf.reply/work-status verbatim")
        (is (= frame-id (:rf.frame/id summary))    ":rf.frame/id verbatim")
        (is (= completed-at-ms (:completed-at summary)) ":completed-at verbatim")))))

(deftest trace-summary-projects-the-error-failure-payload
  (testing "a failure reply's :error map carries owner-local response data — its
            wire-bearing sub-slots are projected through the shared elider too
            (the failure payload is NOT exempt from egress projection)"
    (mk-frame!)
    (let [summary (reply/trace-summary (error-reply) {:frame frame-id})]
      (is (redacted? (get-in summary [:error :token]))
          "a sensitive leaf inside the :error response body is redacted")
      (is (large-marker? (get-in summary [:error :blob]))
          "a large leaf inside the :error response body is elided")
      ;; The error's structural family facts (the :kind / :status) ride — they
      ;; are not at a declared sensitive/large path.
      (is (= :rf.http/http-5xx (get-in summary [:error :kind]))
          "the family error :kind rides (not a declared wire path)")
      (is (= 503 (get-in summary [:error :status]))
          "the family error :status rides (not a declared wire path)")
      (is (= work-id (:work/id summary)) ":work/id verbatim on an error reply"))))

;; ---------------------------------------------------------------------------
;; (2) SENSITIVE wins over LARGE at a both-marked path.
;; ---------------------------------------------------------------------------

(deftest sensitive-wins-over-large-at-a-both-marked-reply-slot
  (testing "EP-0015 — a reply value leaf declared BOTH :sensitive? and :large?
            REDACTS (sensitive wins), it never large-elides to a marker"
    (mk-frame!)
    (let [summary (reply/trace-summary (ok-reply) {:frame frame-id})]
      (is (redacted? (get-in summary [:value :secret-big]))
          "the both-marked leaf is REDACTED, not large-elided")
      (is (not (large-marker? (get-in summary [:value :secret-big])))
          "sensitive wins — no large marker at the both-marked path"))))

;; ---------------------------------------------------------------------------
;; (3) the egress frame is the EXPLICIT :frame opt (or the in-effect carried
;;     scope); missing / unresolved frame FAILS CLOSED. The reply map's OWN
;;     `:rf.frame/id` stamp is an identity fact (rides verbatim) — it is NOT
;;     auto-resolved into the egress policy (rf2-bphg8v).
;; ---------------------------------------------------------------------------

(deftest egress-frame-comes-from-the-explicit-opt-and-fails-closed-when-unresolved
  (testing "the projection frame is supplied by the caller via the explicit
            :frame opt; an UNRESOLVED frame fails closed (whole value redacted) —
            never a raw pass-through, never :rf/default"
    (mk-frame!)
    ;; The explicit :frame opt is KNOWN+LIVE → the policy applies (sensitive redacts).
    (let [known (reply/trace-summary (ok-reply) {:frame frame-id})]
      (is (redacted? (get-in known [:value :token]))
          "a KNOWN :frame opt applies its sensitive policy"))
    ;; A :frame opt that names a NEVER-REGISTERED frame is unresolvable. The
    ;; shared elider fails CLOSED — the whole wire slot conservatively redacts
    ;; rather than shipping the raw body under no policy.
    (let [unresolved (reply/trace-summary (ok-reply) {:frame :reply-egress/ghost})]
      (is (redacted? (:value unresolved))
          "an UNRESOLVED :frame opt fails closed — the whole :value slot redacts")
      (is (not= "bearer-SECRET-do-not-ship" (get-in unresolved [:value :token]))
          "the raw token NEVER ships under an unresolved frame")
      ;; Identity facts still ride (they are not wire slots).
      (is (= work-id (:work/id unresolved))
          ":work/id still rides verbatim — only the wire slots fail closed"))))

;; rf2-wjo28z — pin the carried-stamp seed: `trace-summary` seeds the egress
;; frame from the reply map's own `:rf.frame/id` when no explicit `:frame` opt
;; is given (explicit `:frame` still wins; unresolved still fails closed). So a
;; tool / log forwarder that summarizes a CARRIED reply OUTSIDE any frame scope
;; with NIL opts now applies the CARRIED frame's elision policy to the wire
;; slots — the carried reply SELF-SUMMARIZES. (This deftest was previously the
;; negative control `carried-frame-stamp-is-not-auto-resolved...`; the Mike
;; ruling flipped the behaviour to option (a), so it now asserts resolution.)
(deftest carried-frame-stamp-auto-resolves-into-the-egress-policy
  (mk-frame!)
  ;; No ambient frame scope — bind *current-frame* nil so the ONLY frame source
  ;; is the reply's carried `:rf.frame/id` (now seeded into the egress opts).
  (binding [frame/*current-frame* nil]
    (testing "a carried `:rf.frame/id` (KNOWN+LIVE frame) summarized with NIL opts
              RESOLVES — the carried frame's elision policy is applied to the wire slots"
      (let [reply   (ok-reply)                 ;; carries :rf.frame/id frame-id (a live frame)
            summary (reply/trace-summary reply nil)]
        (is (= frame-id (:rf.frame/id reply))
            "precondition — the reply DOES carry a KNOWN+LIVE frame stamp")
        (is (redacted? (get-in summary [:value :token]))
            "the carried frame's SENSITIVE policy redacts [:token] — the carried stamp self-resolved")
        (is (large-marker? (get-in summary [:value :blob]))
            "the carried frame's LARGE policy elides [:blob]")
        (is (= 3 (get-in summary [:value :public :count]))
            "the unmarked sibling passes through — per-leaf policy, not a whole-slot redact")
        (is (not= "bearer-SECRET-do-not-ship" (get-in summary [:value :token]))
            "the raw token NEVER ships")
        (is (= frame-id (:rf.frame/id summary))
            "the carried :rf.frame/id rides VERBATIM as an identity fact")
        (is (= work-id (:work/id summary))
            ":work/id still rides verbatim")))
    (testing "a carried `:rf.frame/id` naming a NEVER-REGISTERED frame, summarized
              with NIL opts, STILL fails closed (unresolved-stamp companion)"
      (let [reply   (assoc (ok-reply) :rf.frame/id :reply-egress/ghost)
            summary (reply/trace-summary reply nil)]
        (is (redacted? (:value summary))
            "an unresolved carried stamp still fails closed under nil opts")
        (is (= :reply-egress/ghost (:rf.frame/id summary))
            "the carried (unresolved) :rf.frame/id rides verbatim as identity")))
    (testing "an explicit `:frame` opt still WINS over the carried stamp — targeting
              a DIFFERENT (unresolved) frame fails closed even though the reply
              carries a LIVE stamp"
      (let [reply   (ok-reply)                 ;; carries the LIVE frame-id
            summary (reply/trace-summary reply {:frame :reply-egress/ghost})]
        (is (redacted? (:value summary))
            "explicit :frame (unresolved) wins over the carried LIVE stamp — fails closed")))))

;; ---------------------------------------------------------------------------
;; (4) off-box profiles do NOT expose raw bodies; an explicit classified
;;     projection (:local-raw) does. Driven through the record-level
;;     `project-egress` boundary (EP-0015 §10/§11).
;; ---------------------------------------------------------------------------

(deftest off-box-project-egress-redacts-the-reply-body-and-local-raw-exposes-it
  (testing "EP-0015 §10/§11 — the reply value projected through the record-level
            project-egress boundary: off-box profiles redact sensitive + elide
            large; only an explicit :local-raw (a classified projection) exposes
            the raw response body"
    (mk-frame!)
    (doseq [p [:rf.egress/off-box-observability
               :rf.egress/off-box-tool
               :rf.egress/local-redacted]]
      (let [out (rf/project-egress (reply-body)
                  {:frame frame-id :rf.egress/profile p})]
        (is (redacted? (get-in out [:token]))
            (str p ": the sensitive reply-body leaf is redacted off-box"))
        (is (large-marker? (get-in out [:blob]))
            (str p ": the large reply-body leaf is elided off-box"))
        (is (= 3 (get-in out [:public :count]))
            (str p ": the unmarked sibling passes through"))))
    (testing ":rf.egress/local-raw (the explicit classified projection) exposes the raw body"
      (let [out (rf/project-egress (reply-body)
                  {:frame frame-id :rf.egress/profile :rf.egress/local-raw})]
        (is (= "bearer-SECRET-do-not-ship" (get-in out [:token]))
            "trusted-local sees the raw token")
        (is (= big-string (get-in out [:blob]))
            "trusted-local sees the raw large body")))))

(deftest off-box-project-egress-fails-closed-on-an-unresolved-frame
  (testing "EP-0015 / Spec 015 §Direct reads — project-egress for an
            UNRESOLVED carried frame fails closed (the whole reply body
            redacts); it never synthesises :rf/default or ships the raw body.
            (Naming a never-registered frame is the genuine fail-closed case —
            a frame stamp alone is not policy-bearing unless it RESOLVES to a
            live frame.)"
    (let [out (rf/project-egress (reply-body)
                {:frame :reply-egress/ghost :rf.egress/profile :rf.egress/off-box-tool})]
      (is (redacted? out)
          "an unresolved-frame off-box egress conservatively redacts the whole reply body")
      (is (not= "bearer-SECRET-do-not-ship" (get-in out [:token]))
          "the raw token NEVER ships under an unresolved frame"))))

;; ---------------------------------------------------------------------------
;; (5) a relocated / completed reply target does not create an UNPROJECTED
;;     durable / tool / log representation of the raw reply envelope.
;; ---------------------------------------------------------------------------

(deftest a-relocated-reply-target-has-no-unprojected-durable-representation
  (testing "EP-0011/EP-0015 — a relocated (map-completed-event) reply target carries the
            ephemeral accumulator (NOT data-only); its DURABLE projection strips
            it and is data-only, so a stored continuation / ledger row / replay
            log never durably captures a function or a host handle"
    (let [relayed (reply/map-completed-event (fn [event] [:parent/relay event])
                                    [:article/loaded {:id 42}])]
      (is (false? (reply/data-only-target? relayed))
          "a relocated target is NOT data-only (it carries the ::post accumulator)")
      (let [durable (reply/durable-target relayed)]
        (is (true? (reply/data-only-target? durable))
            "the durable projection strips the accumulator — data-only, safe to persist")))))

(deftest a-completed-reply-events-wire-slots-project-before-egress
  (testing "the COMPLETED reply event (the dispatched continuation) still carries
            the raw reply as its final arg in-memory; before that event crosses a
            trace / tool / log boundary its wire slots MUST be projected — the
            shared trace-summary over the appended reply is the egress product,
            and it redacts the sensitive body (no unprojected raw representation
            reaches off-box)"
    (mk-frame!)
    (let [target    [:article/loaded {:id 42}]
          completed (reply/complete target (ok-reply))
          ;; The appended reply rides as the final arg in-memory (raw, by
          ;; design — the reducer needs it). The EGRESS product is the
          ;; trace-summary projection of that reply.
          delivered (peek completed)
          summary   (reply/trace-summary delivered {:frame frame-id})]
      (is (= [:article/loaded {:id 42} (ok-reply)] completed)
          "the completed event carries the raw reply as its final arg in-memory")
      (is (redacted? (get-in summary [:value :token]))
          "the EGRESS projection of the appended reply redacts the sensitive body")
      (is (large-marker? (get-in summary [:value :blob]))
          "the egress projection elides the large body")
      (is (= work-id (:work/id summary))
          "identity facts still ride on the egress product"))))

;; ---------------------------------------------------------------------------
;; ADVERSARIAL CONTROL — the gate above is only as strong as its ability to
;; FAIL when a family bypasses the shared elider. Prove that a RAW reply (the
;; pre-projection in-memory shape) DOES carry the sensitive body, so the
;; redaction assertions above would go RED if trace-summary stopped projecting.
;; ---------------------------------------------------------------------------

(deftest control-the-raw-reply-carries-the-sensitive-body-before-projection
  (testing "the raw reply body (no projection) carries the sensitive token + the
            large blob verbatim — so the projection assertions are non-trivial:
            if trace-summary / project-egress stopped eliding, the sensitive
            body would survive to egress (the exact leak this gate guards)"
    (mk-frame!)
    (let [reply (ok-reply)]
      (is (= "bearer-SECRET-do-not-ship" (get-in reply [:value :token]))
          "the RAW reply carries the sensitive token (pre-projection)")
      (is (= big-string (get-in reply [:value :blob]))
          "the RAW reply carries the large blob (pre-projection)")
      ;; And the SHARED elider, applied directly, is what removes it — proving
      ;; trace-summary delegates to elide-wire-value, not a private elider.
      (let [direct (elision/elide-wire-value (:value reply) {:frame frame-id})]
        (is (redacted? (get-in direct [:token]))
            "elide-wire-value (the shared walker) is what redacts — same result trace-summary produces")))))
