(ns re-frame.security.error-slot-egress-security-cljs-test
  "Security tier — non-schema-validation error-emit slots that carry
  caller-supplied data the path-marks projection did NOT cover and the
  shared schema-aware validation seam does NOT apply to (rf2-zsm03;
  follow-up to the rf2-o69h5 class sweep).

  ## The two slots

  rf2-o69h5 closed the `:rf.error/schema-validation-failure` class: every
  framework emission of that op routes its value-bearing slots through the
  one shared schema-aware redactor. Two OTHER error-emit sites carry
  caller-supplied data outside that class:

    1. `:rf.error/machine-action-exception` `:exception-data` — the `ex-data`
       of a thrown machine action (re-frame.machines.lifecycle-fx.registration).
       The `:event` slot IS covered by the marks projection's
       `project-event-tags`; `:before`/`:after`/`:snapshot` by
       `project-machine-tags`. But `:exception-data` is the developer's
       arbitrary exception payload — under a bare slot NO projection clause
       reached (the op namespace is `:rf.error/*`, not `rf.machine`, so
       `machine-op?` skips it). It can embed the same app secrets the
       machine's `:data` marks gate.

    2. `:rf.route/navigate` `:error` (re-frame.routing.navigate) — `(ex-data
       ex)` of a `route-url` construction throw. On
       `:rf.error/route-url-validation` / `:rf.error/missing-route-param` it
       embeds the raw route-param value (document-ids / tokens) AND the Malli
       explainer. Route-param validation is STRUCTURAL (the throw is from
       `route-url`, not a per-slot Malli walk at this emit point), so the
       shared `redact-validation-tags` seam cannot path-target it.

  ## The fix (mirrors the rf2-o69h5 egress-chokepoint approach)

  Both slots are elided at the trace egress chokepoint BEFORE the event
  crosses the bus / epoch-capture / AI-MCP boundary or reaches a log sink:

    1. the `re-frame.classification` machine-error projection clause (a NEW projection clause,
       wired into `project-trace-event`) elides the WHOLE `:exception-data`
       slot to `:rf/redacted` and stamps `:sensitive? true` when the machine
       declares ANY `:sensitive` mark.
    2. `re-frame.routing.navigate`'s `redact-route-error-tags` elides the
       WHOLE `:error` slot and stamps `:sensitive? true` when the route's
       `:params` / `:query` schema declares any `:sensitive?` slot (decided
       through the SAME shared `:schemas/redact-validation-tags` seam).

  Both stamp `:sensitive? true`, so the MCP egress filter
  (`re-frame.mcp-base.sensitive/strip-sensitive`) ALSO drops the whole event
  when `--allow-sensitive-reads` is disabled (`include? false`, the
  restrictive default) — defence in depth (asserted below).

  ## Net property (verify-by-revert)

  Reverting the machine-error projection clause (or its dispatch clause) to a
  pass-through makes the machine corpus + property go RED — the sentinel
  surfaces in `:exception-data`. Reverting `redact-route-error-tags` makes
  the navigate corpus go RED — the sentinel surfaces in `:error`. Confirmed
  by temporary local revert + restore (see PR Quality gates).

  ## Threat model

  AI/MCP boundary + logs ONLY (per rf2-zsm03 / rf2-o69h5) — human-facing
  egress is out of scope and not gold-plated."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            ;; Publishes the Malli late-bind validate/explain/sensitive hooks;
            ;; without it `route-url` soft-passes (no validation throw) and the
            ;; `:schemas/redact-validation-tags` sensitivity oracle is unbound.
            [re-frame.schemas.malli]
            [re-frame.classification :as classification]
            [re-frame.mcp-base.sensitive :as sens]
            [re-frame.routing :as routing]
            ;; Call `navigate-handler` directly (the `reg-event` handler fn,
            ;; `(cofx event) -> effects-map-or-nil`) so site 2 exercises the
            ;; REAL redaction path headless — no reactive-substrate adapter /
            ;; `dispatch-sync` machinery needed. A `:params`-schema validation
            ;; reject short-circuits inside the handler before any push/scroll
            ;; fx, so a synthetic cofx suffices.
            [re-frame.routing.navigate :as navigate]
            [re-frame.routing.registry :as registry]
            [re-frame.test-support :as test-support]
            ;; SITE 1 drives the REAL production emit (`trace/emit-error!`),
            ;; not direct `classification/project-trace-event` — so the test proves the
            ;; full envelope production actually ships, including the top-level
            ;; `:sensitive?` hoist the MCP egress gate reads (rf2-md2wn0).
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.security.gen :as gen]))

;; Reset per-test so route + mark + app-schema registrations don't bleed.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true}))

;; ---------------------------------------------------------------------------
;; Sentinel — a value that must NEVER appear unredacted in either slot.
;; ---------------------------------------------------------------------------

(def ^:private sentinel "S3CR3T-rf2-zsm03-ERROR-SLOT-DO-NOT-LEAK")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel string appears anywhere (as a
  value - matched as a SUBSTRING - inside a collection, or inside a
  stringified form). Thin wrapper over the shared `gen/contains-string?`
  (rf2-n5bkm7); the sentinel is matched as a substring (`exact? false`)."
  [x]
  (gen/contains-string? x sentinel false))

;; ===========================================================================
;; SITE 1 — :rf.error/machine-action-exception :exception-data
;;
;; Driven through the REAL production emit `trace/emit-error!` — the exact
;; call `re-frame.machines.lifecycle-fx.registration/trace-action-failure!`
;; makes (registration.cljc) — NOT direct `classification/project-trace-event`
;; (rf2-md2wn0). `emit-error!` runs the full production pipeline:
;; `build-event` → marks projection (the machine-error projection clause, which
;; stamps `[:tags :sensitive?]`) → the top-level `:sensitive?` hoist
;; (`hoist-projected-sensitive`) → delivery. We capture the delivered
;; envelope off a trace-tooling listener and assert what production ACTUALLY
;; ships, including the TOP-LEVEL `:sensitive?` flag the MCP egress gate
;; (`mcp-base.sensitive/sensitive-event?`) reads.
;;
;; Why the old shape was a false green: it projected an event directly and
;; asserted `:sensitive?` only under `:tags`. Production hoists `:sensitive?`
;; to the TOP LEVEL during `build-event` — but for machine exceptions the
;; sensitivity is decided LATER (during marks projection), so the top-level
;; flag was never set and MCP egress treated the event as non-sensitive. The
;; direct-projection test proved a shape production did not emit.
;; ===========================================================================

(def ^:private sensitive-machine-id :sec/sensitive-machine)
(def ^:private plain-machine-id     :sec/plain-machine)

(defn- declare-machine-marks!
  "Install the marks a `reg-machine` with `:sensitive` machine-data paths would
  install (machine marks key under the `:event` kind because a machine IS an
  event handler). Post-rf2-ehexnw the marks are DERIVED from the registrar
  `:event` meta, so we register each id as an event carrying the author marks
  (the snapshot analogue the machine-error projection clause keys off) rather than
  poking a deleted side-table."
  []
  ;; Sensitive machine — declares a sensitive `:data` path on its reg meta.
  (rf/reg-event sensitive-machine-id
                {:sensitive [[:data :token]]}
                (fn [_ _] nil))
  ;; Plain machine — no marks at all.
  (rf/reg-event plain-machine-id (fn [_ _] nil)))

(defn- machine-action-exception-tags
  "The EXACT `:tags` shape `trace-action-failure!` hands to
  `trace/emit-error!` (registration.cljc), carrying the sentinel-bearing
  `:exception-data`, with the addressed id keyed under `id-key`.

  the machine-error projection clause resolves the schema lookup via
  `(or (:actor-id tags) (:machine-id tags))` (rf2-yyvtk5): a LIVE actor's
  exception row addresses the throwing instance under `:actor-id` (the
  preferred branch every production emit site now hits —
  registration.cljc), while `:machine-id` is the legacy fallback. We
  parameterise `id-key` so BOTH branches of that `or` are exercised
  (rf2-sxmeqs — the `:actor-id` branch had zero coverage).

  Note: the emit site does NOT stamp `:sensitive?` here — the production
  bug rf2-md2wn0 hinges on that. The flag is decided downstream by marks
  projection, and the top-level hoist must lift it from `:tags`."
  [id-key machine-id]
  {id-key             machine-id
   :action-id         :do/thing
   :state-path        [:running]
   :event             [machine-id [:tick]]
   :exception-message "boom"
   :exception-data    {:user-token sentinel :doc-id [sentinel]}
   :reason            "Machine action threw."
   :recovery          :no-recovery})

(defn- emit-machine-action-exception
  "Drive the REAL production emit: register a trace-tooling listener, call
  `trace/emit-error! :rf.error/machine-action-exception` with the given
  tags (exactly as `trace-action-failure!` does), and return the delivered
  envelope — post-`build-event`, post-marks-projection, post-top-level
  `:sensitive?` hoist. This is the production path; nothing routes around
  the reported gap."
  [tags]
  (let [traces (atom [])
        kw     (keyword "rf2-md2wn0" (name (gensym "machine-ex")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (try
      (trace/emit-error! :rf.error/machine-action-exception tags)
      (finally (trace-tooling/unregister-listener! kw)))
    (first (filter #(= :rf.error/machine-action-exception (:operation %))
                   @traces))))

(defn- emit-machine-action-exception*
  "Emit through the real path with the addressed id keyed under `id-key`."
  [id-key machine-id]
  (emit-machine-action-exception (machine-action-exception-tags id-key machine-id)))

(defn- emit-machine-action-exception-for
  "The legacy `:machine-id`-keyed emit (the `:machine-id` FALLBACK branch of
  the `(or (:actor-id …) (:machine-id …))` lookup)."
  [machine-id]
  (emit-machine-action-exception* :machine-id machine-id))

(deftest machine-exception-data-redacted-for-sensitive-machine
  (testing "rf2-zsm03 / rf2-md2wn0 — a sensitive machine's :exception-data
            is elided to :rf/redacted on the REAL emit path, the TOP-LEVEL
            :sensitive? flag is hoisted (what MCP egress reads), and the
            sentinel never survives"
    (declare-machine-marks!)
    (let [out  (emit-machine-action-exception-for sensitive-machine-id)
          tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (= :rf/redacted (:exception-data tags)) ":exception-data redacted")
      ;; rf2-md2wn0 — the production-critical assertion: TOP-LEVEL, not :tags.
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted")
      (is (not (contains? tags :sensitive?))
          ":sensitive? stripped from :tags after the hoist (no double-stamp)")
      (is (not (contains-sentinel? out))
          (str "the sentinel leaked into the machine-action-exception trace: "
               (pr-str tags)))
      ;; Structural slots survive — consumers locate the failure.
      (is (= sensitive-machine-id (:machine-id tags)) ":machine-id kept")
      (is (= :do/thing (:action-id tags)) ":action-id kept")
      (is (= "boom" (:exception-message tags)) ":exception-message kept"))))

(deftest machine-exception-sensitive-event-drops-at-mcp-egress
  (testing "rf2-md2wn0 — the production fail-closed contract: a sensitive
            machine's exception event is DROPPED by sens/strip-sensitive
            when --allow-sensitive-reads is disabled (include? false, the
            whole-event defence-in-depth layer, not just per-slot redaction).
            This is the assertion the old direct-projection test could never
            make — it only proved the tag-level stamp, which MCP egress does
            not read."
    (declare-machine-marks!)
    (let [out          (emit-machine-action-exception-for sensitive-machine-id)
          [kept dropped] (sens/strip-sensitive [out] false)]
      (is (some? out) "the machine-action-exception trace was delivered")
      ;; The event production actually emits IS classified sensitive by the
      ;; egress filter, so the allow-sensitive-disabled path drops it.
      (is (true? (sens/sensitive-event? out))
          "MCP egress classifies the emitted event as sensitive (top-level flag)")
      (is (= 1 dropped) "the sensitive machine exception event dropped")
      (is (empty? kept) "nothing egressed with --allow-sensitive-reads disabled"))))

(deftest machine-exception-data-verbatim-for-plain-machine
  (testing "rf2-zsm03 — a machine with NO :sensitive mark rides
            :exception-data verbatim (the seam is precise, not a blanket
            scrub) and is NOT classified sensitive at egress"
    (declare-machine-marks!)
    (let [out  (emit-machine-action-exception-for plain-machine-id)
          tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (map? (:exception-data tags)) ":exception-data NOT redacted")
      (is (not (:sensitive? out))
          "no top-level :sensitive? when the machine declares nothing sensitive")
      (is (false? (sens/sensitive-event? out)) "MCP egress treats it as non-sensitive"))))

(deftest machine-exception-data-verbatim-for-unregistered-machine
  (testing "rf2-zsm03 — a machine with no marks entry at all (never
            registered marks) rides :exception-data verbatim"
    ;; No declare-machine-marks! call — the id is unregistered, so the
    ;; registrar carries no :event meta and `registration-classification` derives nil.
    (let [out  (emit-machine-action-exception-for :sec/unregistered)
          tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (map? (:exception-data tags)) ":exception-data verbatim (no marks)")
      (is (not (:sensitive? out)) "no top-level :sensitive? stamp"))))

;; ---------------------------------------------------------------------------
;; SITE 1 — :actor-id PREFERRED branch (rf2-sxmeqs).
;;
;; Every production emit of :rf.error/machine-action-exception addresses the
;; throwing LIVE actor instance under :actor-id (registration.cljc:99); the
;; schema lookup `(or (:actor-id tags) (:machine-id tags))` (marks.cljc:1419)
;; PREFERS that branch. The fixtures above only exercise the :machine-id
;; FALLBACK; these mirror them on the :actor-id key so the privacy-critical
;; preferred branch is proven to redact identically.
;; ---------------------------------------------------------------------------

(deftest machine-exception-data-redacted-for-sensitive-actor
  (testing "rf2-sxmeqs / rf2-md2wn0 — an :actor-id-keyed sensitive-machine
            exception (the PREFERRED lookup branch, the one production emits)
            redacts :exception-data, hoists the TOP-LEVEL :sensitive? flag,
            and the sentinel never survives — on the real emit path"
    (declare-machine-marks!)
    (let [out  (emit-machine-action-exception* :actor-id sensitive-machine-id)
          tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (= :rf/redacted (:exception-data tags)) ":exception-data redacted")
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted")
      (is (not (contains-sentinel? out))
          (str "the sentinel leaked into the :actor-id-keyed exception trace: "
               (pr-str tags)))
      ;; The :actor-id structural slot survives — consumers locate the failure.
      (is (= sensitive-machine-id (:actor-id tags)) ":actor-id kept")
      (is (= :do/thing (:action-id tags)) ":action-id kept")
      (is (= "boom" (:exception-message tags)) ":exception-message kept"))))

(deftest machine-exception-data-verbatim-for-plain-actor
  (testing "rf2-sxmeqs — an :actor-id-keyed machine with NO :sensitive mark
            rides :exception-data verbatim on the preferred branch (the seam
            is precise, not a blanket scrub)"
    (declare-machine-marks!)
    (let [out  (emit-machine-action-exception* :actor-id plain-machine-id)
          tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (map? (:exception-data tags)) ":exception-data NOT redacted")
      (is (not (:sensitive? out))
          "no top-level :sensitive? when the machine declares nothing sensitive"))))

(deftest actor-id-takes-precedence-over-machine-id
  (testing "rf2-sxmeqs — when BOTH keys are present the schema lookup PREFERS
            :actor-id: an :actor-id pointing at the SENSITIVE machine redacts
            even though :machine-id points at the PLAIN one (proves the
            `(or (:actor-id …) (:machine-id …))` precedence, not the fallback)"
    (declare-machine-marks!)
    (let [tags (-> (machine-action-exception-tags :actor-id sensitive-machine-id)
                   (assoc :machine-id plain-machine-id))
          out  (emit-machine-action-exception tags)
          out-tags (:tags out)]
      (is (some? out) "the machine-action-exception trace was delivered")
      (is (= :rf/redacted (:exception-data out-tags))
          ":exception-data redacted via the PREFERRED :actor-id (sensitive)")
      (is (true? (:sensitive? out)) "top-level :sensitive? hoisted from the :actor-id machine")
      (is (not (contains-sentinel? out))
          "the sentinel never survives when :actor-id wins the lookup"))))

;; ---------------------------------------------------------------------------
;; SITE 1 PROPERTY — across arbitrary collection/map nestings of the
;; sentinel inside :exception-data, a sensitive machine NEVER egresses it.
;; ---------------------------------------------------------------------------

(defn- gen-ex-data
  "Generator: a sentinel-bearing ex-data value at a random collection/map
  nesting. `depth` bounds recursion."
  [depth]
  (if (<= depth 0)
    (fn [rng] [sentinel rng])
    (fn [rng]
      (let [[wrap rng1] (gen/rand-nth rng [:map :vector :set :nested-map])
            [inner rng2] ((gen-ex-data (dec depth)) rng1)]
        (case wrap
          :map        [{:k inner} rng2]
          :vector     [[inner] rng2]
          :set        [#{inner} rng2]
          :nested-map [{:a {:b inner}} rng2])))))

(def ^:private gen-nested-ex-data
  (fn [rng]
    (let [[depth rng1] (gen/next-int rng 5)]
      ((gen-ex-data (inc depth)) rng1))))

(deftest sensitive-machine-redacts-ex-data-at-arbitrary-nesting
  (testing "rf2-zsm03 / rf2-sxmeqs / rf2-md2wn0 — across arbitrary nestings of
            the sentinel inside :exception-data, a sensitive machine elides
            the WHOLE slot and hoists the TOP-LEVEL :sensitive?; the sentinel
            never survives AND sens/strip-sensitive drops the event with
            --allow-sensitive-reads disabled. Run over BOTH id-keys so the
            preferred :actor-id branch
            (production) AND the :machine-id fallback both get property
            coverage — on the REAL emit path."
    (declare-machine-marks!)
    (doseq [id-key [:actor-id :machine-id]]
      (let [result (gen/for-all
                     gen-nested-ex-data 120 41
                     (fn [ex-data]
                       (let [tags (assoc (machine-action-exception-tags
                                           id-key sensitive-machine-id)
                                         :exception-data ex-data)
                             out  (emit-machine-action-exception tags)
                             [kept dropped] (sens/strip-sensitive [out] false)]
                         (and (some? out)
                              (= :rf/redacted (-> out :tags :exception-data))
                              (true? (:sensitive? out))
                              (not (contains-sentinel? out))
                              (= 1 dropped)
                              (empty? kept)))))]
        (is (nil? result)
            (str "a sensitive machine leaked :exception-data (or failed to drop "
                 "at egress) for a generated nesting under " id-key ": "
                 (pr-str (when result (dissoc result :threw)))))))))

;; ===========================================================================
;; SITE 2 — :rf.route/navigate :error
;; Driven END-TO-END through the REAL navigate-handler: a validation-reject
;; short-circuits BEFORE any push/scroll fx, so no window stub is needed.
;; ===========================================================================

(def ^:private sensitive-params-schema
  ;; A route :params schema marking the doc-id slot sensitive. A navigate
  ;; with a non-conforming :doc value throws :rf.error/route-url-validation
  ;; whose ex-data carries the failing value (the sentinel) under :value AND
  ;; the Malli explainer.
  [:map [:doc {:sensitive? true} :int]])

(def ^:private plain-params-schema
  [:map [:doc :int]])

(defn- capture-navigate-failure
  "Register `:sec/route` with `params-schema`, invoke the REAL
  `navigate-handler` with a sentinel-bearing (non-:int) :doc param so
  `route-url` throws inside the handler, and return the emitted
  `:rf.error/schema-validation-failure` trace event. Calling the handler
  fn directly (rather than `dispatch-sync`) keeps the test substrate-free:
  the `:params`-schema reject short-circuits before any push/scroll fx."
  [params-schema]
  (rf/reg-route :sec/route {:params params-schema} "/doc/:doc")
  (let [traces (atom [])
        kw     (keyword "rf2-zsm03" (name (gensym "nav")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (try
      ;; :doc must be a value the schema rejects (schema wants :int). The
      ;; sentinel rides as the offending param value.
      ;; The frame stamp reaches the event context under :rf.frame/id
      ;; (rf2-1m6rf1 — the bare :frame coeffect is retired).
      (navigate/navigate-handler {:db {} :rf.frame/id :rf/default}
                                 [:rf.route/navigate :sec/route {:doc sentinel}])
      (finally (trace-tooling/unregister-listener! kw)))
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest navigate-error-redacted-for-sensitive-route
  (testing "rf2-zsm03 — a navigate whose route :params schema is :sensitive?
            elides the :error slot (carrying the failing param value) to
            :rf/redacted, stamps :sensitive?, and never egresses the sentinel"
    (let [trace (capture-navigate-failure sensitive-params-schema)
          tags  (:tags trace)]
      (is (some? trace) "a schema-validation-failure trace fired for the reject")
      (when trace
        (is (= :rf/redacted (:error tags)) ":error slot redacted")
        ;; The redaction stamps tags-level `:sensitive?`; this emit goes
        ;; through the real `trace/emit-error!` → `build-event`, which hoists
        ;; the flag to the TOP LEVEL of the envelope (and strips it from
        ;; `:tags`). The top-level stamp is exactly what the MCP egress gate
        ;; reads (`mcp-base.sensitive/sensitive-event?`).
        (is (true? (:sensitive? trace)) "top-level :sensitive? stamped")
        (is (not (contains-sentinel? trace))
            (str "the sentinel leaked into the navigate :error trace: "
                 (pr-str trace)))
        ;; Structural slots survive.
        (is (= :sec/route (:route-id tags)) ":route-id kept")
        (is (= :event (:where tags)) ":where kept")))))

(deftest navigate-error-verbatim-for-plain-route
  (testing "rf2-zsm03 — a navigate whose route :params schema has NO
            :sensitive? slot rides :error verbatim (no over-redaction)"
    (let [trace (capture-navigate-failure plain-params-schema)
          tags  (:tags trace)]
      (is (some? trace) "a schema-validation-failure trace fired")
      (when trace
        (is (not= :rf/redacted (:error tags)) ":error NOT redacted")
        (is (map? (:error tags)) ":error rode through as the raw ex-data map")
        (is (not (contains? tags :sensitive?))
            "no :sensitive? stamp on a non-sensitive route")))))

;; ===========================================================================
;; REDACTION IS AT-SOURCE — the scrub happens at the trace egress chokepoint,
;; so the sentinel is gone from the event REGARDLESS of the --allow-sensitive-
;; reads opt-in. Unlike the MCP whole-event drop (which would also hide the
;; useful "an action threw" / "a navigate rejected" signal from the agent),
;; per-slot redaction lets the agent SEE the structural error while the secret
;; stays off-box. So we pass the redacted events through the MCP egress with
;; --allow-sensitive-reads ENABLED (include? true — the MOST permissive, fully
;; opted-in path) and confirm the sentinel STILL never reaches the boundary:
;; the protection is the source scrub, not an opt-in decision.
;; ===========================================================================

(deftest redaction-survives-even-the-opt-in-mcp-egress
  (testing "rf2-zsm03 — the per-slot scrub is at-source: even with
            --allow-sensitive-reads fully ENABLED (include? true), neither the
            machine :exception-data nor the navigate :error egresses the
            sentinel"
    (declare-machine-marks!)
    (let [machine-ev (emit-machine-action-exception-for sensitive-machine-id)
          nav-trace  (capture-navigate-failure sensitive-params-schema)
          events     (filterv some? [machine-ev nav-trace])
          [kept _]   (sens/strip-sensitive events true)]
      (is (= 2 (count events)) "both redacted events were produced")
      (is (not-any? contains-sentinel? kept)
          (str "the sentinel reached the MCP boundary even after redaction: "
               (pr-str kept))))))

;; ===========================================================================
;; UNIT — the route-url throw genuinely carries the sentinel in its ex-data
;; (pins the pre-redaction leak vector so the redaction is not vacuous).
;; ===========================================================================

(deftest route-url-throw-ex-data-carries-the-sentinel
  (testing "rf2-zsm03 — the route-url validation throw's ex-data DOES embed
            the failing param value (the sentinel); without redaction the
            navigate :error slot would ship it"
    (rf/reg-route :sec/raw-route {:params sensitive-params-schema} "/doc/:doc")
    (let [ex (try
               (registry/route-url :sec/raw-route {:doc sentinel})
               nil
               (catch #?(:clj Throwable :cljs :default) e e))]
      (is (some? ex) "route-url threw on the non-conforming sensitive param")
      (when ex
        (is (contains-sentinel? (ex-data ex))
            "pre-redaction: the throw's ex-data embeds the sentinel — the leak
             vector the :error redaction closes")))))
