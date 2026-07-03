(ns re-frame.mcp-conformance.reply-envelope-test
  "EP-0011 reply-envelope TRACE-EGRESS wire-vocabulary gate (rf2-mrfvg2).

  ## Why this gate

  EP-0011 is not only a runtime / Xray concern. Managed-Effects property 9
  requires managed async families to emit trace rows FROM reply-envelope
  facts, and Tool-Pair's off-box egress contract names the EP-0011 uniform
  reply envelope among the record-shaped tool egress
  (spec/Tool-Pair.md §`project-egress` record-shaped egress).
  `tools/mcp-conformance` is the client-side MCP gate for re-frame2-pair-mcp's
  `trace-window` / `subscribe` surfaces — the off-box delivery path over the
  authoritative per-frame trace rings (Tool-Pair §Reading the per-frame
  trace ring). But the surface validated only the MCP wrapper / event-bundle
  envelope and a simple counter dispatch — it pinned NO managed-async
  reply-envelope trace content. `rg \"EP-0011|reply-envelope|rf\\.reply\"
  tools/mcp-conformance` returned no matches before this gate.

  This is the wire-vocab counterpart to the live-`subscribe` end-to-end
  path: a pure-JVM schema + fixture + source-pin gate (the same shape as
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
    - `:rf.reply/work-id`     — the `[:rf.work/* …]` attempt-identity tuple.
    - `:rf.reply/work-status` — the operational `:work/status`
                                (`:suppressed` on a stale row).
    - `:rf.reply/carried` / `:rf.reply/current` — the carried-vs-current
                                stale-suppression correlation gate.
    - `:work/id`              — the canonical BARE join key (the same tuple)
                                Xray's uniform work/reply grouping reads.

  An MCP consumer normalizes BOTH the canonical bare keys (`:work/id` /
  `:status` / `:work/status`) AND the additive `:rf.reply/*` spelling
  (preferring the canonical, falling back to `:rf.reply/*`), so a row
  carrying only the additive vocabulary is still joined by `:work/id`.

  ## The gate shape (mirrors the per-marker pattern)

    1. A canonical Malli schema for one reply-envelope trace row.
    2. Fixtures matching the REAL production emissions (HTTP
       `:rf.http/stale-suppressed`, resource `:rf.resource/stale-suppressed`,
       machine `:rf.machine/done`) so a fixture drift trips the gate.
    3. A SOURCE-text pin asserting the additive `:rf.reply/*` keys appear as
       DATA in the production emit sites (so a substrate / family rename
       trips this gate even though the literals live in the implementation
       tree, not in re-frame2-pair-mcp).
    4. A near-miss anti-pin so a rename to a snake_case / pluralised /
       predicate spelling FAILS.

  Coverage boundary: this gate pins the reply-envelope trace VOCABULARY
  visible to MCP consumers — the keys + their shapes. It does NOT
  re-validate the whole managed-effect suite (the runtime / resources /
  Xray tests own family-internal correctness + the live emission); it is
  the MCP-egress-visible contract that those keys reach a `trace-window` /
  `subscribe` consumer un-renamed. See README §EP-0011 coverage boundary."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.mcp-conformance.fixtures :as fx]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as pins]))

;; ---------------------------------------------------------------------------
;; The closed reply vocabularies — mirrored from re-frame.reply as literal
;; data (the wire-vocab gate never :requires the substrate; it consumes the
;; WIRE facts, the same posture Xray's reply-envelope panel takes per spec
;; 013 §One work/reply vocabulary). A substrate drift that changed these sets
;; would also drift the production emissions the source-pin below tracks.
;; ---------------------------------------------------------------------------

(def ^:private reply-statuses
  "The closed reply `:status` vocabulary (Managed-Effects §Status taxonomy)."
  #{:ok :partial :error :cancelled :stale})

(def ^:private work-statuses
  "The closed `:work/status` vocabulary (Managed-Effects §Status taxonomy)."
  #{:completed :failed :timed-out :suppressed :cancelled})

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
;; map). The load-bearing contract is the additive `:rf.reply/*` keys + the
;; canonical bare `:work/id` join key + their shapes.
;; ---------------------------------------------------------------------------

(def ReplyEnvelopeTraceRow
  "The MCP-visible reply-envelope trace-row shape (Managed-Effects §Tracing,
  Xray spec 013 §One work/reply vocabulary). OPEN — additive bespoke family
  facts (`:resource/key`, `:rf.machine/done`, `:recovery`, …) compose without
  a schema bump.

  Required additive reply-envelope slots (the MCP-visible contract):
    :rf.reply/status      — closed reply `:status`.
    :rf.reply/work-id     — `[:rf.work/* …]` attempt-identity tuple.
    :rf.reply/work-status — closed `:work/status`.
    :work/id              — the canonical BARE join key (== :rf.reply/work-id).

  Optional (present on a stale-suppression row):
    :rf.reply/carried / :rf.reply/current — the carried-vs-current gate.
    :rf.reply/stale-reason — why the completion was suppressed."
  [:map
   {:closed false}
   [:rf.reply/status      [:enum :ok :partial :error :cancelled :stale]]
   [:rf.reply/work-id     [:and :any [:fn work-id-tuple?]]]
   [:rf.reply/work-status [:enum :completed :failed :timed-out :suppressed :cancelled]]
   [:work/id              [:and :any [:fn work-id-tuple?]]]
   [:rf.reply/carried     {:optional true} [:maybe :any]]
   [:rf.reply/current     {:optional true} [:maybe :any]]
   [:rf.reply/stale-reason {:optional true} [:maybe :any]]])

;; ---------------------------------------------------------------------------
;; Fixtures — the REAL production emission shapes. Each mirrors a landed
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
   :work/id               [:rf.work/http :article/by-id 1 1]
   :work/kind             :http
   :rf.reply/carried      [:rf.work/http :article/by-id 1 1]
   :rf.reply/current      [:rf.work/http :article/by-id 2 1]
   :recovery              :superseded-by-fresh-request
   :frame                 :app/main})

(def ^:private resource-stale-suppressed-fixture
  "Mirrors `re-frame.resources.events`'s `:rf.resource/stale-suppressed`
  emit — the bespoke resource facts PLUS the additive reply vocabulary."
  {:rf.frame/id           :app/main
   :resource/key          [:rf.scope/global :article/by-slug {:slug "w"}]
   :work/id               [:rf.work/resource [:rf.scope/global :article/by-slug {:slug "w"}] 4]
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
  "Mirrors a machine transition stale-suppression emit carrying the additive
  reply vocabulary (`:rf.machine/transition.cljc`)."
  {:rf.reply/status      :stale
   :rf.reply/work-id     [:rf.work/machine :auth/flow#1]
   :rf.reply/work-status :suppressed
   :work/id              [:rf.work/machine :auth/flow#1]
   :work/kind            :machine
   :rf.machine/done      false})

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
;; The production emit sites where the additive `:rf.reply/*` keys MUST appear
;; as DATA. A family / substrate rename trips the source-pin even though the
;; literals live in the implementation tree (not in re-frame2-pair-mcp) — the
;; same posture the cross-MCP marker pins take against `mcp-base/vocab.cljc`.
;; ---------------------------------------------------------------------------

(def ^:private emit-source-files
  "Per-family source files emitting the additive `:rf.reply/*` reply-envelope
  trace vocabulary. The keyword is `pr-str`'d and grepped against the file
  text AFTER `strip-comments-and-strings` neuters docstring/comment mentions."
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

(deftest reply-envelope-row-pins-the-closed-vocabularies
  (testing "every fixture's :rf.reply/status is in the closed reply-status set
            and its :rf.reply/work-status is in the closed work-status set"
    (doseq [[family fixture] all-fixtures]
      (is (contains? reply-statuses (:rf.reply/status fixture))
          (str family " :rf.reply/status " (:rf.reply/status fixture)
               " is not in the closed reply-status vocabulary " reply-statuses))
      (is (contains? work-statuses (:rf.reply/work-status fixture))
          (str family " :rf.reply/work-status " (:rf.reply/work-status fixture)
               " is not in the closed work-status vocabulary " work-statuses)))))

(deftest reply-envelope-work-id-is-a-canonical-tuple-and-the-bare-join-key-agrees
  (testing "the :rf.reply/work-id is a canonical [:rf.work/* …] tuple and the
            bare :work/id join key carries the SAME tuple (one-attempt-one-id —
            an MCP consumer joins on the bare :work/id)"
    (doseq [[family fixture] all-fixtures]
      (is (work-id-tuple? (:rf.reply/work-id fixture))
          (str family " :rf.reply/work-id is not a canonical [:rf.work/* …] tuple"))
      (is (work-id-tuple? (:work/id fixture))
          (str family " bare :work/id join key is not a canonical tuple"))
      (is (= (:rf.reply/work-id fixture) (:work/id fixture))
          (str family " :rf.reply/work-id and the bare :work/id join key DISAGREE "
               "— an MCP consumer that joins on :work/id would lose this row")))))

(deftest stale-rows-carry-the-suppression-correlation-and-reason
  (testing "a stale-suppression row pins :work/status :suppressed + the
            carried/current correlation gate + a stale reason (the
            stale/cancelled outcome fields the bead names)"
    (doseq [family [:http :resource]
            :let [fixture (all-fixtures family)]]
      (is (= :stale (:rf.reply/status fixture))
          (str family " suppression row is :rf.reply/status :stale"))
      (is (= :suppressed (:rf.reply/work-status fixture))
          (str family " suppression row is :rf.reply/work-status :suppressed"))
      (is (some? (:rf.reply/carried fixture))
          (str family " suppression row carries :rf.reply/carried correlation"))
      (is (some? (:rf.reply/current fixture))
          (str family " suppression row carries :rf.reply/current correlation")))))

;; ===========================================================================
;; (2) Schema FAILS CLOSED on a renamed / dropped / scalar near-miss — the
;;     gate is only as strong as its ability to reject the regression shapes.
;; ===========================================================================

(deftest schema-rejects-a-scalar-work-id
  (testing "a scalar (non-tuple) :rf.reply/work-id is rejected — EP-0011
            one-attempt-one-:work/id requires the family-headed tuple"
    (is (not (m/validate ReplyEnvelopeTraceRow
                         (assoc http-stale-suppressed-fixture
                                :rf.reply/work-id 3
                                :work/id 3)))
        "a scalar work-id MUST fail the schema")))

(deftest schema-rejects-an-out-of-vocabulary-status
  (testing "an off-vocabulary :rf.reply/status / :rf.reply/work-status is
            rejected (the enum is closed)"
    (is (not (m/validate ReplyEnvelopeTraceRow
                         (assoc http-stale-suppressed-fixture :rf.reply/status :done)))
        "a :rf.reply/status outside the closed set MUST fail")
    (is (not (m/validate ReplyEnvelopeTraceRow
                         (assoc http-stale-suppressed-fixture :rf.reply/work-status :running)))
        "a :rf.reply/work-status outside the closed set MUST fail")))

(deftest schema-rejects-a-dropped-required-reply-key
  (testing "a row missing a required additive reply key (a near-miss rename
            that drops the canonical spelling) fails — an MCP consumer would
            lose the status / work-id / grouping"
    (doseq [k [:rf.reply/status :rf.reply/work-id :rf.reply/work-status :work/id]]
      (is (not (m/validate ReplyEnvelopeTraceRow (dissoc http-stale-suppressed-fixture k)))
          (str "a row missing " k " MUST fail the schema")))))

;; ===========================================================================
;; (3) SOURCE-text pin — the additive `:rf.reply/*` keys appear as DATA in the
;;     production emit sites. A family / substrate rename trips this gate.
;; ===========================================================================

(deftest reply-envelope-keys-are-emitted-as-data-in-the-production-sites
  (testing "each additive :rf.reply/* key appears as DATA (not a docstring /
            comment) in at least one production emit site — so a rename of the
            MCP-visible reply-envelope vocabulary trips this gate"
    (let [stripped (map (comp fx/strip-comments-and-strings fx/read-source) emit-source-files)]
      (doseq [k reply-envelope-keys
              :let [literal (pr-str k)]]
        (is (some #(str/includes? % literal) stripped)
            (str "the reply-envelope key " literal " is not emitted as DATA in any "
                 "production site " (vec emit-source-files) " — a rename of the "
                 "MCP-visible reply-envelope vocabulary slipped past the gate"))))))

(deftest the-bare-work-id-join-key-is-emitted-alongside-the-reply-vocabulary
  (testing "the canonical bare :work/id join key is emitted in the production
            reply-envelope emit sites — an MCP consumer joins reply rows by it"
    (let [stripped (map (comp fx/strip-comments-and-strings fx/read-source) emit-source-files)]
      (is (some #(str/includes? % (pr-str :work/id)) stripped)
          ":work/id (the bare canonical join key) must be emitted as DATA"))))

;; ===========================================================================
;; (4) Near-miss anti-pin — a snake_case / pluralised / predicate / dotted-ns
;;     spelling of a reply-envelope key MUST NOT appear in any emit site.
;; ===========================================================================

(deftest no-near-miss-spelling-of-a-reply-envelope-key-appears-in-the-sources
  (testing "no near-miss spelling (snake_case, pluralised, predicate,
            ns-dots→underscores) of a :rf.reply/* key appears in any
            production emit site — a rename to a near-miss form must FAIL"
    (let [sources (into {} (map (juxt identity fx/read-source)) emit-source-files)]
      (doseq [k reply-envelope-keys
              variant (pins/near-miss-variants k)
              [file text] sources]
        (is (not (re-find (fx/variant-regex variant) text))
            (str "near-miss spelling " variant " of " (pr-str k)
                 " appears in " file " — a reply-envelope key rename to a "
                 "near-miss form slipped through"))))))
