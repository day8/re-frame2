(ns re-frame.mcp-conformance.reply-envelope-test
  "Reply-envelope trace-egress wire-vocabulary gate.

  ## Why this gate

  EP-0011 is not only a runtime / Xray concern. Managed-Effects property 9
  requires managed async families to emit trace rows FROM reply-envelope
  facts, and Tool-Pair's off-box egress contract names the EP-0011 uniform
  reply envelope among the record-shaped tool egress
  (spec/Tool-Pair.md §`project-egress` record-shaped egress).
  `tools/mcp-conformance` is the client-side MCP gate for re-frame2-pair-mcp's
  `trace-window` / `watch-epochs` surfaces — the off-box delivery path over the
  authoritative per-frame trace rings (Tool-Pair §Reading the per-frame
  trace ring). Without this gate the surface would validate only the MCP
  wrapper / event-bundle envelope and a simple counter dispatch — no
  managed-async reply-envelope trace content.

  This is the wire-vocab counterpart to the live turn-shaped end-to-end
  path (`live-re-frame2-pair-turn-observation.cjs`): a pure-JVM schema + fixture + source-pin gate (the same shape as
  `event_bundle_test` / the `canonical-markers` table) that pins the
  ADDITIVE `:rf.reply/*` trace vocabulary an MCP consumer reads off a
  `trace-window` / event-bundle `:trace-events` row, so a rename / drop /
  near-miss of the MCP-visible reply-envelope keys FAILS in
  `tools/mcp-conformance`.

  ## The wire vocabulary this pins

  Family completion / stale-suppression trace rows stamp the canonical
  reply facts ADDITIVELY (Xray spec 013 §One work/reply vocabulary +
  Managed-Effects §Tracing). The MCP-visible reply-envelope trace row
  carries, alongside its bespoke family facts:

    - `:rf.reply/status`      — the closed reply `:status` (the row's
                                outcome; `:stale` on a suppression).
    - `:rf.reply/work-id`     — the CANONICAL `[:rf.work/* …]` attempt-identity
                                tuple; the join key the uniform work/reply
                                view groups on.
    - `:rf.reply/work-status` — the operational `:work/status`
                                (`:suppressed` on a stale row).
    - `:rf.reply/carried` / `:rf.reply/current` — the carried-vs-current
                                stale-suppression correlation gate.

  ONE NAME PER FACT. A reply-envelope row carries no bare `:work/id` beside
  `:rf.reply/work-id`; the namespaced `:rf.reply/work-id` is the canonical
  (and only) work-identity spelling on a reply-envelope trace row. (The bare
  `:work/id` lives on the REPLY MAP itself and on non-reply resource-lifecycle rows — the durable
  work-ledger identity — but NOT as a duplicate on a reply-envelope row.) An
  MCP consumer joins on `:rf.reply/work-id`.

  ## The gate shape (mirrors the per-marker pattern)

    1. A canonical Malli schema for one reply-envelope trace row.
    2. Fixtures matching the REAL production emissions (HTTP
       `:rf.http/stale-suppressed`, resource `:rf.resource/stale-suppressed`,
       machine `:rf.machine/done`) so a fixture drift trips the gate.
    3. A per-family SOURCE-text pin asserting every fixture key appears as
       DATA at that family's own emit site (so a key one family drops trips
       this gate even though the literals live in the implementation tree,
       not in re-frame2-pair-mcp, and another family still emits it).
    4. A near-miss anti-pin so a rename to a snake_case / pluralised /
       predicate spelling FAILS.

  Coverage boundary: this gate pins the reply-envelope trace VOCABULARY
  visible to MCP consumers — the keys + their shapes. It does NOT
  re-validate the whole managed-effect suite (the runtime / resources /
  Xray tests own family-internal correctness + the live emission); it is
  the MCP-egress-visible contract that those keys reach a `trace-window` /
  `watch-epochs` consumer un-renamed. See README §EP-0011 coverage boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as rf.mcp-conformance.wire-vocab.source-pins]))

(defn- work-id-tuple?
  "True when `v` is a canonical `[:rf.work/* …]` attempt-identity tuple — a
  non-empty vector whose head is an `:rf.work/`-namespaced keyword. EP-0011
  one-attempt-one-:work/id: the work-id is a family-headed tuple, never a
  scalar."
  [v]
  (and (vector? v)
       (seq v)
       (keyword? (first v))
       (= "rf.work" (namespace (first v)))))

;; ---------------------------------------------------------------------------
;; The canonical reply-envelope trace-row schema. OPEN map — every emission
;; rides its bespoke family facts ALONGSIDE the additive `:rf.reply/*`
;; vocabulary (the row is NOT a single-key wrapper; it is a trace event's tag
;; map). The load-bearing contract is the additive `:rf.reply/*` keys + their
;; shapes. The work identity rides ONLY as `:rf.reply/work-id`; there is no
;; bare `:work/id` duplicate (one name per fact). Its closed enums mirror
;; re-frame.reply's status vocabularies as literal data: the gate consumes
;; the WIRE facts and never :requires the substrate.
;; ---------------------------------------------------------------------------

(def ReplyEnvelopeTraceRow
  "The MCP-visible reply-envelope trace-row shape (Managed-Effects §Tracing,
  Xray spec 013 §One work/reply vocabulary). OPEN — additive bespoke family
  facts (`:resource/key`, `:rf.machine/done`, `:recovery`, …) compose without
  a schema bump.

  Required additive reply-envelope slots (the MCP-visible contract):
    :rf.reply/status      — closed reply `:status`.
    :rf.reply/work-id     — the CANONICAL `[:rf.work/* …]` attempt-identity
                            tuple; the join key (one name per fact; no bare
                            `:work/id` duplicate).
    :rf.reply/work-status — closed `:work/status`.

  Optional (present on a stale-suppression row):
    :rf.reply/carried / :rf.reply/current — the carried-vs-current gate.
    :rf.reply/stale-reason — why the completion was suppressed.

  A bare `:work/id` MUST NOT appear on a reply-envelope row as a duplicate of
  `:rf.reply/work-id` (the trace-catalogue one-name-per-fact lint enforces
  this repo-wide)."
  [:map
   {:closed false}
   [:rf.reply/status      [:enum :ok :partial :error :cancelled :stale]]
   [:rf.reply/work-id     [:and :any [:fn work-id-tuple?]]]
   [:rf.reply/work-status [:enum :completed :failed :timed-out :suppressed :cancelled]]
   [:rf.reply/carried     {:optional true} [:maybe :any]]
   [:rf.reply/current     {:optional true} [:maybe :any]]
   [:rf.reply/stale-reason {:optional true} [:maybe :any]]])

;; ---------------------------------------------------------------------------
;; Fixtures — the REAL production emission shapes. Each mirrors a production
;; `trace/emit!` call so a drift in the production tag map (a dropped key, a
;; renamed key, a scalar work-id) trips this gate.
;; ---------------------------------------------------------------------------

(def ^:private http-stale-suppressed-fixture
  "Mirrors `re-frame.http.transport`'s `:rf.http/stale-suppressed` emit — a
  superseded HTTP request suppressed for a fresh one (carried = superseded
  work-id, current = superseding work-id)."
  {:rf.reply/status       :stale
   :rf.reply/work-status  :suppressed
   :rf.reply/stale-reason :rf.reply/correlation-mismatch
   :rf.reply/work-id      [:rf.work/http :article/by-id 1 1]
   :rf.reply/work-kind    :http
   :rf.reply/carried      [:rf.work/http :article/by-id 1 1]
   :rf.reply/current      [:rf.work/http :article/by-id 2 1]
   :recovery              :superseded-by-fresh-request
   :frame                 :app/main})

(def ^:private resource-stale-suppressed-fixture
  "Mirrors `re-frame.resources.events`'s `:rf.resource/stale-suppressed`
  emit — the bespoke resource facts PLUS the additive reply vocabulary."
  {:rf.frame/id           :app/main
   :resource/key          [:rf.scope/global :article/by-slug {:slug "w"}]
   :generation            4
   :outcome               :superseded
   :rf.reply/status       :stale
   :rf.reply/work-status  :suppressed
   :rf.reply/work-id      [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
   :rf.reply/stale-reason :resource/generation-mismatch
   :rf.reply/correlation  {:generation {:carried 4 :current 5}}
   :rf.reply/carried      {:work/id [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4] :generation 4}
   :rf.reply/current      {:work/id [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 5] :generation 5}})

(def ^:private machine-stale-suppressed-fixture
  "Mirrors `re-frame.machines.lifecycle-fx.finalize`'s `:rf.machine/done` emit
  for a stale late spawn completion (the parent instance is gone) — the
  bespoke actor facts PLUS the additive reply vocabulary, with the stale
  reason and correlation riding on the stale arm."
  {:actor-id              :auth/flow#1
   :output                nil
   :parent-id             :auth/root
   :error?                false
   :frame                 :app/main
   :rf.reply/work-kind    :machine
   :rf.reply/status       :stale
   :rf.reply/work-id      [:rf.work/machine :auth/flow#1 [:authenticating] 1]
   :rf.reply/work-status  :suppressed
   :rf.reply/stale-reason :rf.machine/actor-not-live
   :rf.reply/correlation  {:generation {:carried 1 :current nil}}})

(def ^:private all-fixtures
  {:http     http-stale-suppressed-fixture
   :resource resource-stale-suppressed-fixture
   :machine  machine-stale-suppressed-fixture})

;; ---------------------------------------------------------------------------
;; The MCP-visible reply-envelope keys this gate pins.
;; ---------------------------------------------------------------------------

(def ^:private reply-envelope-keys
  "The additive `:rf.reply/*` trace keys an MCP consumer reads off a
  reply-envelope trace row. A rename / drop of any of these breaks the
  uniform work/reply join an MCP consumer performs."
  [:rf.reply/status
   :rf.reply/work-id
   :rf.reply/work-status
   :rf.reply/carried
   :rf.reply/current])

;; ---------------------------------------------------------------------------
;; The production emit sites. The literals live in the implementation tree
;; (not in re-frame2-pair-mcp) — the same posture the cross-MCP marker pins
;; take against `mcp-base/vocab.cljc`.
;; ---------------------------------------------------------------------------

(def ^:private family-emit-sites
  "Each fixture family's ONE production emit site, as `[rel-path head]`
  pairs naming the form whose tag map the fixture mirrors (see
  `source-form`). The resource tag map is assembled across one call — the
  bespoke facts at the family's call site, the `:rf.reply/*` facts merged on
  in the shared emitter it calls — so that family names both forms."
  {:http     [["implementation/http/src/re_frame/http/transport.cljc"
               "(defn emit-superseded-stale-trace!"]]
   :resource [["implementation/resources/src/re_frame/resources/events.cljc"
               "(defn- emit-resource-stale-suppressed!"]
              ["implementation/resources/src/re_frame/resources/reply_handlers.cljc"
               "(defn emit-stale-suppressed!"]]
   :machine  [["implementation/machines/src/re_frame/machines/lifecycle_fx/finalize.cljc"
               "(rf.trace/emit! :rf.machine :rf.machine/done"]]})

(def ^:private emit-source-files
  "Source files emitting the additive `:rf.reply/*` reply-envelope trace
  vocabulary, swept whole by the near-miss anti-pin."
  ["implementation/http/src/re_frame/http/transport.cljc"
   "implementation/resources/src/re_frame/resources/events.cljc"
   "implementation/machines/src/re_frame/machines/transition.cljc"
   "implementation/routing/src/re_frame/routing/nav_token.cljc"])

;; ===========================================================================
;; (1) Schema conformance — every production-shaped fixture validates.
;; ===========================================================================

(deftest reply-envelope-fixtures-conform-to-schema
  (doseq [[family fixture] all-fixtures]
    (is (m/validate ReplyEnvelopeTraceRow fixture)
        (str family " reply-envelope trace fixture failed schema validation:\n"
             (me/humanize (m/explain ReplyEnvelopeTraceRow fixture))))))

;; ===========================================================================
;; (2) Schema FAILS CLOSED on a renamed / dropped / scalar near-miss — the
;;     gate is only as strong as its ability to reject the regression shapes.
;; ===========================================================================

(deftest schema-rejects-each-regression-shape
  ;; Each row is the http fixture with one regression applied. EP-0011
  ;; one-attempt-one-work-id requires the family-headed tuple; both status
  ;; enums are closed; and a dropped required key (a near-miss rename that
  ;; drops the canonical spelling) loses an MCP consumer the status,
  ;; work-id or grouping.
  (doseq [[label row]
          [["a scalar (non-tuple) :rf.reply/work-id"
            (assoc http-stale-suppressed-fixture :rf.reply/work-id 3)]
           ["a :rf.reply/status outside the closed set"
            (assoc http-stale-suppressed-fixture :rf.reply/status :done)]
           ["a :rf.reply/work-status outside the closed set"
            (assoc http-stale-suppressed-fixture :rf.reply/work-status :running)]
           ["a row missing :rf.reply/status"
            (dissoc http-stale-suppressed-fixture :rf.reply/status)]
           ["a row missing :rf.reply/work-id"
            (dissoc http-stale-suppressed-fixture :rf.reply/work-id)]
           ["a row missing :rf.reply/work-status"
            (dissoc http-stale-suppressed-fixture :rf.reply/work-status)]]]
    (is (not (m/validate ReplyEnvelopeTraceRow row))
        (str label " MUST fail the schema"))))

;; ===========================================================================
;; (3) SOURCE-text pin — every fixture key appears as DATA at its own family's
;;     emit site. A key one family drops or renames trips this gate even while
;;     another family still emits it.
;; ===========================================================================

(deftest each-fixture-key-is-emitted-as-data-at-its-family-emit-site
  (doseq [[family fixture] all-fixtures
          :let [sites (family-emit-sites family)
                text  (str/join "\n" (map #(apply rf.mcp-conformance.fixtures/source-form %) sites))]
          k (keys fixture)]
    (is (re-find (rf.mcp-conformance.fixtures/variant-regex (pr-str k)) text)
        (str family " fixture key " (pr-str k) " is not emitted as DATA at its "
             "emit site " sites " — the fixture has drifted from production"))))

;; ===========================================================================
;; (4) Near-miss anti-pin — a snake_case / pluralised / predicate / dotted-ns
;;     spelling of a reply-envelope key MUST NOT appear in any emit site.
;; ===========================================================================

(deftest no-near-miss-spelling-of-a-reply-envelope-key-appears-in-the-sources
  (testing "no near-miss spelling (snake_case, pluralised, predicate,
            ns-dots→underscores) of a :rf.reply/* key appears in any
            production emit site — a rename to a near-miss form must FAIL"
    (let [sources (into {} (map (juxt identity rf.mcp-conformance.fixtures/read-source)) emit-source-files)]
      (doseq [k reply-envelope-keys
              variant (rf.mcp-conformance.wire-vocab.source-pins/near-miss-variants k)
              [file text] sources]
        (is (not (re-find (rf.mcp-conformance.fixtures/variant-regex variant) text))
            (str "near-miss spelling " variant " of " (pr-str k)
                 " appears in " file " — a reply-envelope key rename to a "
                 "near-miss form slipped through"))))))
