(ns day8.re-frame2-xray.panels.resources-helpers
  "Pure-data projection + the `:rf.resource/*` trace-family declaration
  for Xray's Resources tab (Spec 016 §Xray and AI tooling).

  ## Why a `.cljc` helpers split

  Same contract as every other panel's `*_helpers.cljc`: the projection
  algebra (registry rows, live-instance rows, work-ledger rows, the
  route/resource graph, the lifecycle timeline, the invalidation graph,
  the cache-growth view, and the lints) is PURE data — no substrate,
  no Reagent/UIx, no DOM. Keeping it here means the algebra runs
  under the JVM unit-test target and the CLJS view (`resources.cljs`)
  stays a thin hiccup renderer over these projections.

  ## The Resources panel's decoupled read path

  THIS ns `:require`s nothing under `implementation/resources/`, and
  neither does the view over it — the projection works off registrar,
  runtime-db and trace data alone. That is a fact about the PANEL's read
  path, NOT about the Xray package: `tools/xray/deps.edn` declares
  `day8/re-frame2-resources` at top level, and the Derivation-Graph tab —
  a SEPARATE surface — `:require`s `re-frame.resources.tooling`
  (rf2-1fc459). `tools/xray/spec/024-Resources-Panel.md` §Decoupling
  keeps the two apart; do not collapse them. The panel reads everything
  decoupled, exactly the way the Routing tab reads the route slice and
  the Machine Inspector reads machine snapshots:

    - the STATIC resource registry via `(rf/registrations {:source :store :kind :resource})`
      (process-global registrar) — no require;
    - the LIVE per-frame instance table from the runtime-db partition
      slice at `[:rf.runtime/resources :entries]` — no require;
    - the LIVE per-frame work ledger from `[:rf.runtime/work-ledger]`;
    - the trace stream (`:rf.resource/*` op rows) from the trace buffer.

  The reserved key paths below are duplicated as small literal constants
  rather than read from `re-frame.resources.state` — duplicating three
  reserved keywords is the price of keeping THIS projection free of a
  `re-frame.resources.*` require edge. They are the
  reserved runtime-db keys fixed in [Conventions §Reserved runtime-db
  keys]; a drift would be caught by the panel's CLJS wiring test.

  ## PRIVACY (Spec 016 §Xray and AI tooling — load-bearing)

  Tool surfaces PREFER SUMMARIES over raw values. Params, scopes, AND
  data get the SAME privacy + size elision — scopes carry user ids,
  tenant ids, locale, and impersonation markers, so a scope is exactly
  as sensitive as data. The panel NEVER renders a raw param/scope/data
  value; it renders a `summarize`d shape (type + bounded size + a small
  redaction-aware preview). The on-box render additionally routes each
  payload slot through the framework's wire-elision walker under the
  observed frame (rf2-9zix0u). This ns supplies the IN-PANEL summary safe to
  render to a human operator and bounded so a huge data blob never
  floods the panel.

  Xray MUST NOT become an owner by observing (Spec 016 §Active owners
  and causes): every projection here is a PURE READ. Nothing in this ns
  dispatches `:rf.resource/ensure`, attaches an owner, refetches, or
  extends GC — inspection has zero side effects on resource liveness."
  #?(:cljs (:require [clojure.string :as str])
     :clj  (:require [clojure.string :as str])))

;; ---------------------------------------------------------------------------
;; Reserved runtime-db paths (decoupled literals — see ns docstring).
;; ---------------------------------------------------------------------------

(def resources-key
  "Reserved runtime-db key for the resource cache subtree
  (`:rf.runtime/resources`). Per Spec 016 §Cache home and write
  authority. Duplicated literal — this ns does not require the resources
  artefact (see the ns docstring's read-path note)."
  :rf.runtime/resources)

(def work-ledger-key
  "Reserved runtime-db key for the frame work ledger subtree
  (`:rf.runtime/work-ledger`). Per Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(def entries-rel-path
  "Runtime-db-relative path to the cache entries map. Per Spec 016."
  [resources-key :entries])

(defn resource-payload-path-suffix
  "The lowered-declaration path SUFFIX, relative to an entry's `key-id`, a
  payload `slot-key` re-roots under (rf2-aw9cfs) — mirrors
  `re-frame.resources.classification/instance-declaration-paths`'s re-rooting:
  a `:data`-rooted declaration lands directly under the entry (`[key-id
  :data]`); a `:scope`- / `:params`-rooted one lands under the scoped-key
  `:resource/key` carrier at index 0 / 2 (the scoped key is `[scope
  resource-id params]`). `:error` / `:refresh-error` are not currently lowered
  by the resources registry but live at the entry's own key, so they re-root
  there too — a harmless no-match today that is correct the day the registry
  starts classifying them.

  The absolute egress `:path` for a slot is
  `(into (conj (vec entries-rel-path) key-id) (resource-payload-path-suffix
  slot-key))`. Canonical home for the pure path logic so every caller — the
  on-box Resources-panel
  egress (`resources/on-box-resource-egress-fn`, rf2-9zix0u) today — re-roots each slot
  to the SAME absolute coordinate the resources artefact lowers its per-instance
  `:sensitive?` / `:large?` declarations to — one source of truth, no drift."
  [slot-key]
  (case slot-key
    :scope  [:resource/key 0]
    :params [:resource/key 2]
    [slot-key]))

(def tag-index-rel-path
  "Runtime-db-relative path to the reverse tag index."
  [resources-key :tag-index])

(def owner-index-rel-path
  "Runtime-db-relative path to the reverse owner index."
  [resources-key :owner-index])

(def routing-key
  "Reserved runtime-db key for the routing slice subtree
  (`:rf.runtime/routing`). The route/resource graph reads the live
  `:current` route + the per-nav-token unsettled-blocking set from here
  (Spec 016 §Route integration). Duplicated literal — this ns does not
  require the routing artefact; mirrors the literal the resources
  route integration uses (rf2-m5u3gt)."
  :rf.runtime/routing)

(def routing-blocking-key
  "The per-nav-token unsettled-blocking subtree key under the routing slice
  (`:resource-blocking`). `[:rf.runtime/routing :resource-blocking
  <nav-token>]` holds `{<key-id> <scoped-key>}` for the resources whose
  blocking ensure has NOT yet settled for that navigation (Spec 016 §Route
  integration — the SSR / route wait points). Byte-keyed, so two `=`-equal
  requirements with different collection KINDS stay two wait points
  (rf2-btdl1)."
  :resource-blocking)

;; ---------------------------------------------------------------------------
;; The `:rf.resource/*` trace family (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------
;;
;; The runtime EMITS these rows across the resource artefact —
;; `:rf.resource/registered` (`registry.cljc`, frame-agnostic),
;; `:rf.resource/scope-resolved` (`scope_registry.cljc` — a named
;; resource-scope resolver resolved an `{:from-db …}` reference to a
;; concrete scope (or nil); EP-0016 D3 / Spec 016 §Named resource-scope
;; resolvers), `:owner-attached` / `:cache-hit` / `:deduped` / `:fetch-started` /
;; `:work-started` (`events.cljc` ensure path), `:invalidated`,
;; `:refetch-decision`, `:revalidate-scan` (focus/reconnect scan summary),
;; `:owner-released`, `:removed`, `:succeeded` / `:failed` /
;; `:refresh-failed`, `:stale-suppressed`, `:stale-scheduled` /
;; `:stale-fired` / `:gc-scheduled` / `:gc-fired` / `:gc-skipped` /
;; `:poll-scheduled` / `:poll-fired` (`timers.cljc` + `events.cljc`;
;; poll = EP-0020), `:work-abort-requested`, the EP-0021 infinite-feed
;; load-more ops `:load-more` / `:page-appended` / `:page-failed` /
;; `:load-more-skipped` (`events.cljc` — the next-page fetch start, the
;; tail-append success, the THIRD error channel, and the terminal /
;; in-flight / no-feed no-op), `:route-plan`
;; (`route.cljc` — route-entry planning), `:hydrated` / `:hydrate-refetch`
;; / `:hydrate-clock-skew` and `:restored` / `:restore-clock-skew`
;; (`ssr.cljc` — SSR hydration + epoch/SSR restore reconcile). The
;; per-file emit-site catalogue is Spec 009 §Where trace emission lives
;; (the resources artefact entry) + Xray spec 024 §The `:rf.resource/*`
;; trace family. Xray DEFINES the family (its
;; closed operation set, per-op colour class, and a human label) so the
;; Resources tab + the Trace tab can colour, group, and filter resource
;; rows without re-deriving the vocabulary.
;;
;; NOTE: `:rf.resource/ensure` / `:rf.resource/refetch` /
;; `:rf.resource/window-focused` / etc. are dispatched EVENT IDs, not
;; emitted trace operations — they appear in the stream only as the
;; `:rf.event/dispatched` event vector, never as a `:rf.resource/*`
;; `:operation`, so they are NOT family members of `trace-ops`.
;;
;; `:rf.resource/cache-hit` is a FRESH-SKIP ensure — an `ensure` of an
;; already-`:loaded` entry still fresh-by-policy serves the cached value
;; (no fetch, no in-flight join), distinct from `:deduped`. Emitted by the
;; runtime (`events.cljc` ensure path) — see Spec 016 §Xray and AI tooling.
;;
;; Each op carries, where applicable: frame, work id, scope, resource
;; key/id, params summary, generation, request id, owner, cause, status
;; before/after, work status, resource/invalidated tags, freshness
;; timestamps, and redaction/size markers (Spec 016).

(def trace-family-prefix
  "The reserved trace-family namespace prefix for resource trace rows.
  An operation keyword whose namespace is `\"rf.resource\"` belongs to
  the resource family. Per [Conventions §Reserved namespaces]."
  "rf.resource")

(def trace-ops
  "The closed `:rf.resource/*` trace-family operation set with the
  per-op semantic class + a short human label, ordered roughly by
  lifecycle. The `:class` keys the panel + Trace-tab colour mapping
  (`op-class->token`): `:lifecycle` (neutral progress), `:success`,
  `:failure`, `:dedupe` (a join/cache-hit, no new work), `:invalidation`,
  `:gc`, `:suppression` (stale reply suppressed — a correctness event),
  and `:hydration`. Per Spec 016 §Xray and AI tooling (the trace-family
  enumeration)."
  {:rf.resource/registered           {:class :lifecycle    :label "registered"}
   :rf.resource/scope-resolved       {:class :lifecycle    :label "scope resolved"}
   :rf.resource/owner-attached       {:class :lifecycle    :label "owner attached"}
   :rf.resource/cache-hit            {:class :dedupe       :label "cache hit"}
   :rf.resource/deduped              {:class :dedupe       :label "deduped"}
   :rf.resource/fetch-started        {:class :lifecycle    :label "fetch started"}
   :rf.resource/work-started         {:class :lifecycle    :label "work started"}
   :rf.resource/work-abort-requested {:class :lifecycle    :label "abort requested"}
   :rf.resource/work-completed       {:class :success      :label "work completed"}
   :rf.resource/succeeded            {:class :success      :label "succeeded"}
   :rf.resource/failed               {:class :failure      :label "failed"}
   :rf.resource/refresh-failed       {:class :failure      :label "refresh failed"}
   ;; EP-0021 — the infinite-feed load-more ops. A `load-more` issues the next
   ;; page fetch (lifecycle, an APPEND start); `page-appended` is the page
   ;; success (a tail append, success-class); `page-failed` is the THIRD error
   ;; channel (a load-more failure that KEPT the feed, failure-class, distinct
   ;; from first-load `:failed` / background `:refresh-failed`);
   ;; `load-more-skipped` is a no-op (terminal / in-flight join / no-feed —
   ;; dedupe-class, like a cache-hit, no new work). Spec 009 §Where trace
   ;; emission lives + Xray 024 §The `:rf.resource/*` trace family.
   :rf.resource/load-more            {:class :lifecycle    :label "load more"}
   :rf.resource/page-appended        {:class :success      :label "page appended"}
   :rf.resource/page-failed          {:class :failure      :label "page failed"}
   :rf.resource/load-more-skipped    {:class :dedupe       :label "load more skipped"}
   :rf.resource/invalidated          {:class :invalidation :label "invalidated"}
   :rf.resource/refetch-decision     {:class :lifecycle    :label "refetch decision"}
   :rf.resource/revalidate-scan      {:class :lifecycle    :label "revalidate scan"}
   :rf.resource/route-plan           {:class :lifecycle    :label "route plan"}
   :rf.resource/owner-released       {:class :lifecycle    :label "owner released"}
   :rf.resource/stale-scheduled      {:class :gc           :label "stale scheduled"}
   :rf.resource/stale-fired          {:class :gc           :label "stale fired"}
   :rf.resource/gc-scheduled         {:class :gc           :label "gc scheduled"}
   :rf.resource/gc-fired             {:class :gc           :label "gc fired"}
   :rf.resource/gc-skipped           {:class :gc           :label "gc skipped"}
   :rf.resource/poll-scheduled       {:class :gc           :label "poll scheduled"}
   :rf.resource/poll-fired           {:class :gc           :label "poll fired"}
   :rf.resource/removed              {:class :lifecycle    :label "removed"}
   :rf.resource/stale-suppressed     {:class :suppression  :label "stale suppressed"}
   :rf.resource/hydrated             {:class :hydration    :label "hydrated"}
   :rf.resource/hydrate-refetch      {:class :hydration    :label "hydrate refetch"}
   :rf.resource/hydrate-clock-skew   {:class :hydration    :label "hydrate clock skew"}
   :rf.resource/restored             {:class :hydration    :label "restored"}
   :rf.resource/restore-clock-skew   {:class :hydration    :label "restore clock skew"}})

(defn resource-trace-op?
  "True iff `operation` (a trace event's `:operation`) is a member of the
  `:rf.resource/*` trace family — either an explicitly-enumerated op in
  `trace-ops` OR any keyword in the reserved `rf.resource` namespace (so
  a future op the runtime adds is still recognised as family-member for
  colouring/filtering before this enum is extended)."
  [operation]
  (boolean
    (and (keyword? operation)
         (or (contains? trace-ops operation)
             (= trace-family-prefix (namespace operation))))))

(defn op-class
  "The semantic class keyword for a resource trace `operation` (one of
  `:lifecycle` / `:success` / `:failure` / `:dedupe` / `:invalidation` /
  `:gc` / `:suppression` / `:hydration`), or `:lifecycle` for an
  in-namespace op not yet enumerated. nil for a non-family op."
  [operation]
  (when (resource-trace-op? operation)
    (get-in trace-ops [operation :class] :lifecycle)))

(defn op-label
  "A short human label for a resource trace `operation`. Falls back to
  the bare op name for an in-namespace op not yet enumerated."
  [operation]
  (when (keyword? operation)
    (get-in trace-ops [operation :label] (name operation))))

(def mutation-trace-family-prefix
  "The reserved trace-family namespace prefix for mutation trace rows
  (`:rf.mutation/*` — started / succeeded / failed / replied /
  stale-suppressed / the three optimistic lifecycle ops). Per
  [Conventions §Reserved namespaces]."
  "rf.mutation")

(def projection-warning-ops
  "The two `:rf.warning/*` ops the resources panel's trace-borne projections
  read: the `:on-conflict :force` clobber warning (rendered as its own §6d
  run) and the write-side scope-mismatch tripwire (which suppresses a
  duplicate optimistic-reach finding). Enumerated because the `:rf.warning`
  namespace is shared framework-wide — unlike `rf.resource` / `rf.mutation`,
  a prefix test there would admit every warning the runtime emits."
  #{:rf.warning/optimistic-force-clobber
    :rf.warning/mutation-scope-mismatch})

(defn resource-projection-op?
  "True iff a trace `operation` can feed ANY of this panel's trace-borne
  projections — the `:rf.resource/*` family, the `:rf.mutation/*` family, or
  one of `projection-warning-ops`.

  This is the PRE-FILTER predicate (rf2-y8doi.15). The composite used to hand
  the WHOLE trace buffer to a dozen projections, each of which re-scanned it
  end to end on every trace row; filtering once to the family's own share and
  feeding the projections from that leaves each projection's own filter intact
  (they stay correct on an unfiltered buffer) while dropping the per-row cost
  to the share of trace rows this panel can actually use.

  Deliberately WIDER than any single projection's filter: a future
  `:rf.mutation/*` or `:rf.resource/*` op is retained by prefix, so adding a
  projection never needs this predicate widened first — the failure mode of
  the opposite choice is a section that silently reads empty."
  [operation]
  (boolean
    (and (keyword? operation)
         (or (resource-trace-op? operation)
             (= mutation-trace-family-prefix (namespace operation))
             (contains? projection-warning-ops operation)))))

;; ---------------------------------------------------------------------------
;; PRIVACY — summarization with size + redaction elision (Spec 016).
;; ---------------------------------------------------------------------------

(def ^:private default-preview-budget
  "Max characters of a value's `pr-str` rendered as the in-panel
  preview before the `:elided?` marker replaces the tail. Bounded so a
  large data blob never floods the panel; a value-level egress walk uses
  the framework `egress-value` size walker instead."
  120)

(def redacted-sentinel
  "The framework sensitive-redaction sentinel. A value already redacted
  upstream (the runtime emits `:rf/redacted` for `:sensitive?` slots via
  `project-egress`) renders as `[redacted]`, not as a raw preview."
  :rf/redacted)

(def large-elided-sentinel
  "The framework size-elision sentinel — a `:large?` slot the runtime
  elided on the wire."
  :rf.size/large-elided)

(defn- value-type-tag
  "A short, render-safe type tag for a value — used as the summary head
  so the operator sees the SHAPE without the raw contents."
  [v]
  (cond
    (nil? v)        "nil"
    (map? v)        "map"
    (set? v)        "set"
    (vector? v)     "vector"
    (sequential? v) "seq"
    (string? v)     "string"
    (keyword? v)    "keyword"
    (number? v)     "number"
    (boolean? v)    "boolean"
    :else           "value"))

(defn- value-size
  "A bounded count for a value — element count for a collection, char
  count for a string, else nil (scalars have no size)."
  [v]
  (cond
    (string? v) (count v)
    (coll? v)   (count v)
    :else       nil))

(defn summarize
  "PRIVACY-PRESERVING summary of a param / scope / data value for
  IN-PANEL rendering (Spec 016 §Xray and AI tooling — \"prefer summaries
  over raw values\"). Params, scopes, and data ALL flow through this same
  fn — a scope is exactly as sensitive as data (it carries user/tenant/
  locale/impersonation ids).

  Returns a render-safe map:

      {:type      \"map\"            ; the value's shape
       :size      2                  ; element / char count (nil for scalars)
       :preview   \"{:slug \\\"welc…\"  ; bounded pr-str, tail elided past budget
       :elided?   true              ; preview was truncated
       :redacted? false             ; value is the :rf/redacted sentinel
       :large?    false}            ; value is the :rf.size/large-elided sentinel

  A value already redacted/elided upstream (the runtime emits the
  framework sentinels for `:sensitive?` / `:large?` slots) keeps its
  sentinel status and renders no raw preview. NEVER returns the raw
  value — the caller renders the summary map, not the value."
  ([v] (summarize v nil))
  ([v {:keys [budget] :or {budget default-preview-budget}}]
   (let [redacted? (= v redacted-sentinel)
         large?    (= v large-elided-sentinel)
         pr        (when-not (or redacted? large?) (pr-str v))
         elided?   (and pr (> (count pr) budget))
         preview   (cond
                     redacted? "[redacted]"
                     large?    "[large — elided]"
                     elided?   (str (subs pr 0 budget) "…")
                     :else     pr)]
     {:type      (value-type-tag v)
      :size      (value-size v)
      :preview   preview
      :elided?   (boolean elided?)
      :redacted? redacted?
      :large?    large?})))

(defn scoped-key-summary
  "Summarize a scoped resource key `[scope resource-id params]` for a
  table row. The scope and params each go through `summarize` (PRIVACY:
  scope carries PII, params identify the remote read), while the
  resource-id is a plain keyword (a registry name, never PII). Returns
  `{:scope <summary> :resource-id <kw> :params <summary>}`. A malformed
  key (not a 3-vector) summarizes the whole key as a single value.

  Optional `egress-fn` (rf2-e0mq7a): `(fn [value] -> egressed)` applied to
  the PII-bearing scope + params BEFORE `summarize`, so an off-box
  (AI / log) caller routes those values through the framework
  `egress-value` walker — a `:sensitive?` slot summarizes as `[redacted]`
  and a `:large?` slot as `[large — elided]`. The resource-id is a registry
  name (never PII), so it is never egressed. The in-panel caller omits the
  fn — the in-panel `summarize` is sufficient for human rendering and the
  values never leave the box."
  ([scoped-key] (scoped-key-summary scoped-key nil))
  ([scoped-key egress-fn]
   (let [eg (or egress-fn identity)]
     (if (and (vector? scoped-key) (= 3 (count scoped-key)))
       (let [[scope rid params] scoped-key]
         {:scope       (summarize (eg scope))
          :resource-id rid
          :params      (summarize (eg params))})
       {:scope       (summarize (eg scoped-key))
        :resource-id nil
        :params      (summarize nil)}))))

;; ---------------------------------------------------------------------------
;; ON-BOX sensitive-resource redaction for the TRACE-BORNE projections
;; (rf2-y8doi.15, extending rf2-9zix0u).
;;
;; rf2-9zix0u closed the on-box leak for the LIVE INSTANCE rows: their payload
;; slots route through the observed frame's `:sensitive` classification before
;; `summarize`, so a sensitive resource renders `[redacted]` rather than a
;; 120-char `pr-str` preview on a screen-shared panel. That gate is keyed on a
;; runtime-db PATH (`[:rf.runtime/resources :entries <key-id> …]`), which a
;; TRACE row does not have — a trace value carries no key-id and no runtime-db
;; coordinate — so the same fn cannot be reused here.
;;
;; What a trace row DOES carry is the RESOURCE-ID, in its `:resource/key`
;; scoped key (`[scope resource-id params]`) and in its `:resource-id` tag. The
;; static registry carries the coarse root `:sensitive?` declaration per
;; resource (`registry-row`). Joining those two gives the on-box gate for the
;; trace-borne sections: a row that NAMES a `:sensitive?` resource redacts its
;; value-bearing slots, exactly as that resource's instance row already does.
;;
;; The rule is deliberately ROW-level rather than slot-level: a row's scope,
;; params, cause and matched keys are all identity-bearing evidence about the
;; same read, so redacting some of them and printing the rest is the leak in a
;; different shape. The METADATA (operation, label, class, resource-id,
;; generation, work-id, owner, status, tags, counts) is never redacted — a
;; redacted row still shows the whole lifecycle SHAPE.
;;
;; NOT applied to `scope-resolutions`: its rows are named-scope RESOLVER
;; resolutions, whose `:resource-id` tag carries the RESOLVER id (see the
;; `:rf.resource/scope-resolved` emit in the resources artefact's scope
;; registry), not a resource-id — so a resource-id-keyed set cannot decide
;; them, and the framework rules that surface's redaction unconditional and
;; OFF-BOX-owned (its own off-box projector), with the on-box listener keeping
;; the raw evidence. Gating it on this set would be dead code.
;; ---------------------------------------------------------------------------

(defn sensitive-resource-ids
  "The set of resource-ids whose STATIC registry row declares `:sensitive?
  true` — the coarse root privacy prop `registry-row` projects. `rows` is
  `project-registry`'s output. Pure; nil ⇒ `#{}`."
  [rows]
  (into #{} (comp (filter :sensitive?) (map :resource-id)) (or rows [])))

(defn- scoped-key-resource-id
  "The resource-id carried at index 1 of a canonical scoped key `[scope
  resource-id params]`; nil for a malformed key."
  [scoped-key]
  (when (and (vector? scoped-key) (= 3 (count scoped-key)))
    (nth scoped-key 1)))

(defn- names-sensitive?
  "True iff any of `resource-ids` is declared `:sensitive?`. nil-safe both
  ways, so the default (no set threaded) is always false."
  [sensitive-rids resource-ids]
  (boolean (and (seq sensitive-rids)
                (some #(contains? sensitive-rids %) resource-ids))))

(defn- sensitive-eg
  "The per-row value gate. Returns `eg` unchanged for a row that names no
  `:sensitive?` resource, and a fn collapsing EVERY value to the framework
  `redacted-sentinel` for one that does — `summarize` then renders
  `[redacted]` and no preview, identically to an upstream-redacted slot."
  [eg sensitive?]
  (if sensitive? (constantly redacted-sentinel) eg))

;; ---------------------------------------------------------------------------
;; Static resource registry projection (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------

(defn- describe-scope-policy
  "A human description of a resource's `:scope` POLICY (not a resolved
  scope value — the registry carries the policy). A policy is EXACTLY one
  of two shapes (Spec 016 §Every resource declares a scope policy):
  `:rf.scope/global` — the audit-surface flag (Spec 016 §scope audit
  surface) — or a `{:from-db <id>}` NAMED RESOLVER reference, labelled by
  the resolver id it names. Anything else never survives registration, so
  it reads as MISSING here."
  [scope]
  (cond
    (= scope :rf.scope/global) {:policy :global   :label "global (explicit claim)" :global? true}
    (and (map? scope)
         (contains? scope :from-db))
    {:policy :resolver :label (str "named resolver " (:from-db scope)) :global? false}
    :else                      {:policy :missing  :label "MISSING"                 :global? false}))

(defn- request-summary
  "A one-line, render-safe summary of a resource's `:request` (a fn that
  lowers params → a Spec 014 managed-HTTP args map). The fn body is
  opaque, so the summary names the transport + that the request is
  fn-derived; the live wire details surface per-instance via the work
  ledger's `:transport`."
  [spec]
  (let [transport (or (:transport spec) :rf.http/managed)]
    {:transport transport
     :fn?       (boolean (:request spec))
     :label     (str (name transport)
                     (when (:request spec) " · request fn"))}))

(defn registry-row
  "Project ONE static registry entry `[resource-id meta]` (a
  `(rf/registrations {:source :store :kind :resource})` row, whose `:rf/resource` slot carries
  the registration spec) into a render-safe registry row. Per Spec 016
  §Xray and AI tooling (the static resource registry):

      {:resource-id    :article/by-slug
       :doc            \"Article detail by slug.\"
       :params-schema  <summary>   ; schemas can be large — summarized
       :data-schema    <summary>
       :request        {:transport :rf.http/managed :fn? true :label …}
       :scope          {:policy :global :label … :global? true}
       :stale-after-ms 60000
       :gc-after-ms    300000
       :tags?          true        ; a :tags producer fn is declared
       :sensitive?     false
       :large?         false
       :source-coord   {:file … :line …}}

  `declaring-routes` is injected by the composite (it is a cross-source
  join over the route registry — see `attach-declaring-routes`)."
  [[resource-id meta]]
  (let [spec (:rf/resource meta)]
    {:resource-id    resource-id
     :doc            (or (:doc spec) (:doc meta))
     :params-schema  (summarize (:params-schema spec))
     :data-schema    (summarize (:data-schema spec))
     :request        (request-summary spec)
     :scope          (describe-scope-policy (:scope spec))
     :stale-after-ms (:stale-after-ms spec)
     :gc-after-ms    (:gc-after-ms spec)
     :tags?          (boolean (:tags spec))
     :sensitive?     (boolean (:sensitive? spec))
     :large?         (boolean (:large? spec))
     :source-coord   (when (or (:file meta) (:line meta))
                       {:file (:file meta) :line (:line meta)})
     :declaring-routes []}))

(defn- route-resource-ids
  "The set of resource-ids a route declares via its `:resources`
  metadata vector (each entry is a `{:resource <id> …}` map)."
  [route-meta]
  (into #{}
        (keep :resource)
        (:resources route-meta)))

(defn attach-declaring-routes
  "Cross-join the registry rows against the route registry: for each
  registry row, attach the vector of route-ids whose `:resources`
  metadata declares that resource (Spec 016 §Route integration — Xray
  can display which routes own a resource). `routes-map` is
  `(rf/registrations {:source :store :kind :route})` (`{<route-id> <meta>}`); pure, no require
  on the routing artefact."
  [rows routes-map]
  (let [rid->routes (reduce-kv
                      (fn [acc route-id route-meta]
                        (reduce (fn [acc rid]
                                  (update acc rid (fnil conj []) route-id))
                                acc (route-resource-ids route-meta)))
                      {} (or routes-map {}))]
    (mapv (fn [row]
            (assoc row :declaring-routes
                   (vec (sort-by str (get rid->routes (:resource-id row) [])))))
          rows)))

(defn project-registry
  "Project the full static resource registry into sorted render-safe
  rows with declaring-routes joined. `registrations` is
  `(rf/registrations {:source :store :kind :resource})`; `routes-map` is `(rf/registrations {:source :store :kind :route})` (or nil). Per Spec 016 §Xray and AI tooling."
  [registrations routes-map]
  (-> (mapv registry-row (or registrations {}))
      (attach-declaring-routes routes-map)
      (->> (sort-by (comp str :resource-id)) vec)))

;; ---------------------------------------------------------------------------
;; Named resource-scope resolver registry projection (rf2-hls77w, EP-0016 D3 /
;; Spec 016 §Named resource-scope resolvers). The `:resource-scope` registrar
;; kind carries each resolver's canonical spec under `:rf/resource-scope`
;; (`{:inputs {name [:db rf-path]} :whole-db? :resolve …}`). The panel reads
;; it decoupled via `(rf/registrations {:source :store :kind :resource-scope})` — no require on the
;; resources artefact, exactly like the resource/route registries above.
;; ---------------------------------------------------------------------------

(defn- input-descriptor-summary
  "A render-safe summary of one declared resolver input source descriptor
  `[:db <rf-path>]`. The source HEAD (`:db`) is a plain keyword (not PII).
  The rf-path NAMES which app-db location decides the scope identity — a
  structural fact tooling shows so an operator can explain the derivation
  (EP-0015 disposition 8: declared inputs make the resolver a member of the
  derived-sensitivity graph). The path is `summarize`d (bounded) for safety,
  never the VALUE at it (the value is PII and surfaces only via the
  egress-projected `:rf.resource/scope-resolved` trace at resolution time)."
  [descriptor]
  (if (and (vector? descriptor) (= 2 (count descriptor)))
    (let [[source rf-path] descriptor]
      {:source source
       :path   (summarize rf-path)})
    {:source nil
     :path   (summarize descriptor)}))

(defn scope-resolver-row
  "Project ONE `(rf/registrations {:source :store :kind :resource-scope})` entry `[scope-id meta]`
  (whose `:rf/resource-scope` slot carries the canonical resolver spec) into
  a render-safe row. Per Spec 016 §Named resource-scope resolvers / §Xray and
  AI tooling:

      {:scope-id     :realworld/session
       :doc          \"…\"
       :inputs       [{:name :username
                       :source :db
                       :path   {:type \"vector\" :size 3 :preview …}}]
       :whole-db?    false          ; true for the explicit-cost fn sugar
       :source-coord {:file … :line …}}

  Surfaces the resolver id + its DECLARED inputs (names + `[:db path]`
  sources, paths summarized) + the whole-db-sugar cost flag — the static
  facts tooling reads to explain which app facts decide a resource identity
  and to mark the whole-db cost. NEVER renders an input VALUE or a resolved
  scope (those are PII surfaced only via the egress-projected
  `:rf.resource/scope-resolved` trace at resolution time)."
  [[scope-id meta]]
  (let [spec (:rf/resource-scope meta)]
    {:scope-id     scope-id
     :doc          (or (:doc spec) (:doc meta))
     :inputs       (->> (:inputs spec)
                        (mapv (fn [[input-name descriptor]]
                                (assoc (input-descriptor-summary descriptor)
                                       :name input-name)))
                        (sort-by (comp str :name))
                        vec)
     :whole-db?    (boolean (:whole-db? spec))
     :source-coord (when (or (:file meta) (:line meta))
                     {:file (:file meta) :line (:line meta)})}))

(defn project-scope-resolvers
  "Project the full static resource-scope resolver registry into sorted
  render-safe rows. `registrations` is `(rf/registrations {:source :store :kind :resource-scope})`
  (`{<scope-id> <meta>}`). Per Spec 016 §Named resource-scope resolvers /
  §Xray and AI tooling."
  [registrations]
  (->> (or registrations {})
       (mapv scope-resolver-row)
       (sort-by (comp str :scope-id))
       vec))

;; ---------------------------------------------------------------------------
;; Live instance table projection (Spec 016 §Xray and AI tooling).
;; ---------------------------------------------------------------------------

(defn- derive-stale?
  "Pure freshness derivation (Spec 016 §Status semantics — `:stale?` is a
  derived value, never a stored fact): an entry is stale iff it was
  invalidated, or `now-ms` has passed its `:stale-at`. nil `now-ms` or a
  nil `:stale-at` falls back to the invalidation flag alone.

  ## Second implementation, deliberately (rf2-vyvo8)

  This MIRRORS `re-frame.resources.state/entry-stale?`, which Spec 016
  §Stale and GC scheduling calls \"the single shared derivation\". The
  sibling is the canonical one; this is the panel's copy, and the copy is
  a CHOICE rather than a necessity — `tools/README.md` permits a tool to
  `:require` from `implementation/`, and only the reverse is forbidden.
  What it buys is the panel's decoupled read path: this file carries no
  `re-frame.resources.*` require edge, so the projection reads the
  runtime-db shape rather than the artefact's API (see the ns docstring
  above and `tools/xray/spec/024-Resources-Panel.md` §Decoupling — the
  Xray PACKAGE does declare the artefact). Spec 016's
  claim enumerates four RUNTIME readers — the fresh-skip gate, the SSR
  projection, the stale-timer re-check and the `:stale?` sub — so a tool
  projection sits outside the set it governs.

  DO NOT \"fix\" this by delegating to the sibling: the two are NOT
  interchangeable. The canonical predicate is not nil-clock safe
  (`(>= nil stale-at)` throws on the JVM), whereas `project-instances`
  has a 1-arity that passes `now-ms` as nil by design, so delegation
  would be a regression rather than a tidy-up.

  The duplication is held honest by a test-only pin —
  `stale-derivation-agrees-with-framework-pin` in
  `resources_helpers_cljs_test.cljc` — which runs both derivations over a
  shared matrix and fails the moment they disagree. The require edge it
  needs lives in test code, so the pin costs this surface nothing."
  [entry now-ms]
  (boolean
    (or (some? (:invalidated-at entry))
        (and now-ms (:stale-at entry) (>= now-ms (:stale-at entry))))))

(defn- gc-eligible?
  "An entry is GC-eligible (Spec 016 §Invalidation / §Stale and GC) when
  it has NO active owners — an inactive entry is left stale / eligible
  for `:gc-after-ms` cleanup. Owners keep a resource alive."
  [entry]
  (empty? (:active-owners entry)))

(defn- entry-has-data?
  "Derived `:has-data?` (Spec 016 §Status semantics — derived, not stored):
  the entry currently carries usable last-known-good `:data`. A `nil`
  `:data` is no-data; a value already redacted/elided UPSTREAM (`:rf/redacted`
  / `:rf.size/large-elided`) means data WAS present (the sentinel replaced a
  real value), so it counts as has-data (rf2-tgm1xu — the egress redaction
  of a payload must not flip the derived `:has-data?` fact).

  EP-0021 R1 (rf2-o5iv): an `:infinite?` feed's `:data` is the ordered PAGE
  VECTOR, seeded EMPTY (`[]`) before page 0 loads — an empty page vector is
  NO usable data, so the feed reads first-load (`:loading`), not `:fresh`.
  This mirrors the SINGLE canonical derivation in
  `re-frame.resources.state/has-data?`; the projection must agree with it or
  the row contradicts its own `:status` / `:page-count`. The page-vector rule
  applies only to an ACTUAL vector: an infinite payload already collapsed to
  a redaction/elision sentinel upstream keeps the `some?` reading above."
  [entry]
  (let [data (:data entry)]
    (if (and (:infinite? entry) (sequential? data))
      (boolean (seq data))
      (some? data))))

(defn instance-row
  "Project ONE live cache entry `[scoped-key entry]` into a render-safe
  instance-table row (Spec 016 §Xray and AI tooling — the live
  resource-instance table). PRIVACY: scope + params + data summarized.

      {:scoped-key      <opaque-id-for-react-key>   ; the RAW key (identity)
       :scope           <summary>     ; PII — summarized
       :resource-id     :article/by-slug
       :params          <summary>     ; identity — summarized
       :status          :loaded
       :stale?          false
       :has-data?       true
       :data            <summary>     ; payload — summarized
       :error           <summary>     ; failure envelope — summarized
       :refresh-error   <summary>
       :loaded-at       …  :stale-at … :invalidated-at …
       :generation      4
       :attempt         2
       :request-id      <id>
       :current-work    <work-id>
       :active-owners   [<owner> …]   ; owners are tokens, not PII
       :owner-count     1
       :tags            [<tag> …]
       :gc-eligible?    false}

  Optional `egress-fn` (rf2-tgm1xu, rf2-aw9cfs): `(fn [value slot-key key-id]
  -> egressed)` applied to the PAYLOAD-bearing values (the key's
  scope/params + the entry's `:data`/`:error`/`:refresh-error`) BEFORE
  `summarize`, so a `:sensitive?` slot summarizes as `[redacted]` and a
  `:large?` slot as `[large — elided]` on the off-box path. `key-id` is the
  entry's own `map-key` (the CEDN-1 byte key-id the live `:entries` map uses,
  rf2-9e0tyq) — threaded so the off-box `resource-egress-fn` can re-root its
  declaration-matching `:path` at the SAME absolute coordinate the resources
  artefact lowers its per-instance `:sensitive?` / `:large?` declarations to
  (a slot-only path, missing the key-id, never matches — rf2-aw9cfs). The
  METADATA (status, owners, tags, request-id, generation, timestamps) is
  projected from the raw entry and NEVER routed through egress — a redacted
  summary STILL exposes the metadata. The RAW `scoped-key` is kept verbatim
  as `:scoped-key` (the identity / react key), so per-row egress can never
  collapse two entries whose scope/params redact to the same sentinel. A
  `nil` slot is left as nil (nothing to redact) so the derived `:has-data?`
  fact survives."
  ([entry+key now-ms] (instance-row entry+key now-ms nil))
  ([[map-key entry] now-ms egress-fn]
   (let [egress       (or egress-fn (fn [v _slot _key-id] v))
         eg           (fn [v slot] (when (some? v) (egress v slot map-key)))
         ;; rf2-9e0tyq — the `:entries` map key is now the opaque CEDN-1 byte
         ;; `key-id` STRING; the kind-preserving scoped-key VECTOR lives on the
         ;; entry as `:resource/key`. Read the scope/rid/params from there (fall
         ;; back to the map key for a legacy entry that lacks the stamp).
         scoped-key   (or (:resource/key entry) map-key)
         [raw-scope raw-rid raw-params]
                      (if (and (vector? scoped-key) (= 3 (count scoped-key)))
                        scoped-key
                        [scoped-key nil nil])
         raw-data     (:data entry)]
     (cond-> {:scoped-key     scoped-key
              :scope          (summarize (eg raw-scope :scope))
              :resource-id    (or raw-rid (:resource/id entry))
              :params         (summarize (eg raw-params :params))
              :status         (:status entry)
              :stale?         (derive-stale? entry now-ms)
              :has-data?      (entry-has-data? entry)
              :data           (summarize (eg raw-data :data))
              :error          (when (:error entry) (summarize (eg (:error entry) :error)))
              :refresh-error  (when (:refresh-error entry) (summarize (eg (:refresh-error entry) :refresh-error)))
              :loaded-at      (:loaded-at entry)
              :stale-at       (:stale-at entry)
              :invalidated-at (:invalidated-at entry)
              :generation     (:generation entry)
              :attempt        (:attempt entry)
              :request-id     (:request-id entry)
              :current-work   (:current-work entry)
              :active-owners  (vec (:active-owners entry))
              :owner-count    (count (:active-owners entry))
              :tags           (vec (:tags entry))
              :gc-eligible?   (gc-eligible? entry)}
       ;; EP-0021 — the INFINITE-FEED surface (R1/R2/R3). All pure functions of
       ;; the durable entry: an `:infinite?` feed carries an ordered page vector
       ;; in `:data`, a runtime-owned `:next-page-param` cursor (nil = the single
       ;; terminal), and the THIRD error channel `:page-error` (a load-more
       ;; failure that KEPT the feed). `:page-count` is the page-vector length;
       ;; `:terminal?` is the derived `:has-next-page?` complement. The cursor is
       ;; egress-projected (a cursor can carry record ids — Spec 016 §Tooling).
       ;; `:page-params` is the ORDERED per-page cursor chain — the durable
       ;; record of how the accumulation advanced (page 0's nil seed, then each
       ;; resolved next-page param). It is explicitly NOT part of the feed cache
       ;; key, so it is the only tool-facing record of the page-by-page fetch
       ;; sequence (Spec 016 §Tooling — "per-page params … since cursors can
       ;; carry ids"; EP-0021). Each element is a cursor value the SAME shape as
       ;; `:cursor`, so each is egress-projected `:params`-slot identically (the
       ;; rf2-3tysyj cursor-egress treatment) — never the raw cursor.
       ;; `:fetching-next?` (a load-more vs a whole-feed refresh) is NOT a pure
       ;; function of the entry — it joins the in-flight work record's
       ;; `:page-index` (see `work-row` `:page-index`); the panel reads it off
       ;; the §3 work-ledger join, not here.
       (:infinite? entry)
       (merge (let [next-param (:next-page-param entry)]
                {:infinite?      true
                 :page-count     (count (:data entry))
                 :cursor         (summarize (eg next-param :params))
                 :page-params    (mapv #(summarize (eg % :params))
                                       (:page-params entry))
                 :terminal?      (nil? next-param)
                 :has-next-page? (some? next-param)
                 :page-error     (when (:page-error entry)
                                   (summarize (eg (:page-error entry) :error)))}))))))

(defn project-instances
  "Project a frame's live resource entries map `{<scoped-key> <entry>}`
  into sorted render-safe instance rows. Sorted by resource-id then
  generation (descending) so the most-recent attempt leads. `now-ms` is
  the freshness clock (the panel passes the current ms; tests pass a
  fixed clock). Per Spec 016 §Xray and AI tooling.

  Optional `egress-fn` (rf2-tgm1xu) is threaded to `instance-row` so the
  on-box render redacts the payload values (scope/params/data) BEFORE
  summarization while the metadata projects from the raw entry. A caller
  may omit it — the bare `summarize` is sufficient for human
  rendering and the runtime-db never leaves the box."
  ([entries] (project-instances entries nil nil))
  ([entries now-ms] (project-instances entries now-ms nil))
  ([entries now-ms egress-fn]
   (->> (or entries {})
        (mapv #(instance-row % now-ms egress-fn))
        (sort-by (juxt (comp str :resource-id) (comp - (fnil identity 0) :generation)))
        vec)))

;; ---------------------------------------------------------------------------
;; Live work-ledger table projection (Spec 016 §Frame work ledger —
;; the 2026-06-10 EP amendment: per-frame work-ledger table joined to
;; resource entries).
;; ---------------------------------------------------------------------------

(def terminal-work-statuses
  "Terminal work-ledger statuses (Spec 016 §Ledger row retention). A
  terminal row is pruned on the linked entry's next successful
  transition; the panel marks them so the operator distinguishes live
  work from a recent-races tail."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn work-row
  "Project ONE work-ledger record `[work-id record]` into a render-safe
  row (Spec 016 §Frame work ledger / 2026-06-10 EP amendment). Raw host
  handles (AbortControllers, timeout handles, promises) live OUTSIDE
  durable frame-state in side tables and are STRUCTURALLY inaccessible to
  this projection — the ledger record carries only serializable facts:

      {:work-id      <id>
       :kind         :resource
       :resource/key <scoped-key-summary>  ; PRIVACY: scope/params summarized
       :resource-id  :article/by-slug
       :generation   4
       :status       :running
       :terminal?    false
       :owners       [<owner> …]
       :causes       [<cause> …]            ; causes are summarized (may carry data)
       :cancellable? true
       :deadline-at  1780752005100
       :attempt      2
       :transport    :rf.http/managed
       :outcome      <summary>}"
  [[map-key record]]
  (let [rkey (:resource/key record)
        {:keys [scope resource-id params]} (scoped-key-summary rkey)
        ;; rf2-9e0tyq / rf2-hgy5kf: the `:rf.runtime/work-ledger` map is keyed
        ;; on the opaque CEDN-1 byte `work-id-id` STRING; the kind-preserving
        ;; work-id VECTOR is carried on the record as `:work/id`. Read it from
        ;; there (fall back to the map key for a legacy record that lacks the
        ;; stamp) so the displayed `:work-id` is the kind-preserving identity,
        ;; NOT the byte string.
        work-id (or (:work/id record) map-key)]
    {:work-id      work-id
     :kind         (or (:work/kind record) (:kind record))
     :resource/key {:scope scope :resource-id resource-id :params params}
     :resource-id  resource-id
     :generation   (:generation record)
     :status       (:status record)
     :terminal?    (contains? terminal-work-statuses (:status record))
     :owners       (vec (:owners record))
     :causes       (mapv summarize (:causes record))
     :cancellable? (boolean (:cancellable? record))
     :deadline-at  (or (:deadline-at record) (:deadline record))
     :attempt      (or (:attempt record) (:retry-attempt record))
     :transport    (:transport record)
     ;; EP-0021 — the recorded page index of an infinite-feed work record: 0
     ;; (a page-0 first-load / whole-feed refetch) or a positive tail index (a
     ;; load-more APPEND). A LIVE positive-index row is the durable fact behind
     ;; the `:fetching-next?` derived sub (a load-more in flight, distinct from
     ;; a whole-feed `:fetching?` refresh — Spec 016 §Causal event — load-more
     ;; R2); nil for a non-infinite work record.
     :page-index   (:page-index record)
     :outcome      (when (contains? record :outcome) (summarize (:outcome record)))}))

(defn project-work-ledger
  "Project a frame's work-ledger map `{<work-id> <record>}` into sorted
  render-safe rows — non-terminal (live) work first, then the terminal
  recent-races tail; within each group sorted by generation descending.
  Per Spec 016 §Frame work ledger."
  [ledger]
  (->> (or ledger {})
       (mapv work-row)
       (sort-by (juxt :terminal? (comp - (fnil identity 0) :generation)))
       vec))

;; ---------------------------------------------------------------------------
;; Route / resource graph (Spec 016 §Route integration / §Xray).
;; ---------------------------------------------------------------------------

(defn routing-current
  "Extract the live `:current` route slice from the routing-runtime subtree
  (`{:rf.runtime/routing {:current {:route-id … :nav-token … :params … :path …}}}`
  shape, read decoupled off the target frame's runtime-db). Returns the
  `:current` map (carrying `:route-id` + `:nav-token`) or nil when no route is
  active (rf2-m5u3gt). Pure — accepts the already-extracted routing slice."
  [routing-slice]
  (when (map? routing-slice)
    (:current routing-slice)))

(defn routing-blocking-keys
  "Extract the live UNSETTLED-blocking scoped keys from the routing-runtime
  subtree. `[:resource-blocking <nav-token>]` is a map of per-nav-token
  `{<key-id> <scoped-key>}` maps naming the resources whose blocking ensure
  has not yet settled (rf2-btdl1); this returns their scoped-key VALUES.

  TWO arities (rf2-cduftx F2):

  - `[routing-slice nav-token]` — the SCOPED read: returns ONLY the scoped
    keys still unsettled for `nav-token`. This is the form the route graph
    must use for the CURRENT route's `:blocking-live`, per Spec 024
    §Route/resource graph (\"the declared blocking resources whose scoped
    keys are still in the PER-nav-token unsettled-blocking set\"). A nil
    `nav-token` (no active route) ⇒ `[]` — a route with no live navigation
    has no live wait point.

  - `[routing-slice]` — the ALL-TOKEN flatten: every unsettled key across
    every coexisting nav-token bucket. Retained for an all-routes / global
    wait-point diagnostic; it must NOT be used to flag a single route's
    `:blocking-live`, because an OLD token's bucket can still hold a key
    that shares a resource-id with the current route, which would falsely
    report the active route as blocked by a wait point belonging to a
    superseded navigation (the rf2-cduftx F2 cross-token bleed).

  Pure; nil/missing ⇒ `[]`."
  ([routing-slice]
   (let [by-token (when (map? routing-slice)
                    (get routing-slice routing-blocking-key))]
     (into [] (mapcat vals) (vals (or by-token {})))))
  ([routing-slice nav-token]
   (let [by-token (when (map? routing-slice)
                    (get routing-slice routing-blocking-key))]
     (if (some? nav-token)
       (into [] (vals (get by-token nav-token)))
       []))))

(defn- resource-liveness
  "Aggregate the LIVE state of one declared resource-id across the projected
  `instance-rows` + `work-rows` (rf2-m5u3gt). The route graph node is static
  (a registry declaration), but the operator's bug-class question is whether
  THIS route's read is stale, fresh, or just fetching — so each node carries
  a `:live` rollup over the cache entries + work ledger for its resource-id:

      {:entry-count 2      ; cached entries for this resource-id (any scope/params)
       :has-data?   true   ; ≥1 cached entry currently carries data
       :stale?      false  ; ≥1 cached entry is derived-stale
       :statuses    #{:loaded}   ; the set of live entry statuses
       :active-work 1      ; non-terminal work-ledger rows for this resource-id
       :freshness   :fresh}  ; :fresh / :stale / :loading / :error / :idle / :none

  `:freshness` is the headline: `:loading` (active work and no usable data
  yet — checked first so a mid-fetch read with no cache entry still reads
  loading), `:none` (no cached entry AND no active work), `:error` (≥1 entry
  at `:status :error`), `:stale` (≥1 stale entry), `:fresh` (cached, has
  data, not stale), else `:idle`. Pure over the already-projected rows; nil
  live inputs ⇒ a `:none` rollup so the static graph still renders.

  `:error` sits BEFORE `:stale` because a blocking SSR wait point whose
  first load FAILED carries no data and no live work, so without it the
  rollup fell to `:idle` — the graph painted a failed wait point as if
  nothing had been asked of it, which is the bug class Xray spec 024
  §Bug classes names (\"a permanent skeleton … did the last background
  refresh fail?\"). `:error` is a first-class entry status per Spec 016
  §Status semantics, and `freshness-colour` in the view already carried an
  `:error` arm this rollup could never reach."
  [resource-id instance-rows work-rows]
  (let [rows  (filterv #(= resource-id (:resource-id %)) (or instance-rows []))
        works (filterv #(and (= resource-id (:resource-id %))
                             (not (:terminal? %)))
                       (or work-rows []))
        has-data?    (boolean (some :has-data? rows))
        stale?       (boolean (some :stale? rows))
        error?       (boolean (some #(= :error (:status %)) rows))
        active-work  (count works)
        statuses     (into #{} (keep :status) rows)
        freshness    (cond
                       ;; active work without usable data yet ⇒ :loading
                       ;; (checked BEFORE :none so a route whose resource is
                       ;; mid-fetch with no cache entry surfaces as loading,
                       ;; not "nothing here")
                       (and (pos? active-work) (not has-data?)) :loading
                       (and (empty? rows) (zero? active-work)) :none
                       error?                                :error
                       stale?                                :stale
                       has-data?                             :fresh
                       :else                                 :idle)]
    {:entry-count (count rows)
     :has-data?   has-data?
     :stale?      stale?
     :statuses    statuses
     :active-work active-work
     :freshness   freshness}))

(defn- route-resource-node
  "Project ONE `:resources` entry on a route into a graph node. The
  `:params` resolver is a fn (opaque) and `:scope` is a declared override
  (a concrete value or a `{:from-db <id>}` reference — never a fn, since
  rf2-kuky.83 retired that tier), so the node records THAT each is
  declared, the blocking flag (SSR wait point), the keep-previous flag, the
  local `:id` + `:after` dependency, and any `:when` guard — the structure
  Xray draws without parsing handlers. When
  live inputs are supplied (rf2-m5u3gt) the node also carries a `:live`
  rollup of the resource-id's current cache/work state."
  [entry instance-rows work-rows]
  {:resource        (:resource entry)
   :blocking?       (boolean (:blocking? entry))
   :keep-previous?  (boolean (:keep-previous? entry))
   :local-id        (:id entry)
   :after           (vec (:after entry))
   :when?           (boolean (:when entry))
   :params-fn?      (boolean (:params entry))
   :scope-resolver? (boolean (:scope entry))
   :live            (resource-liveness (:resource entry) instance-rows work-rows)})

(defn project-route-graph
  "Project the route registry into the route/resource graph (Spec 016
  §Route integration): for every route declaring `:resources`, a node
  carrying its declared resources, blocking-vs-non-blocking split, SSR
  wait points (the blocking nodes), and any `:after` dependency waterfall.
  `routes-map` is `(rf/registrations {:source :store :kind :route})`.

      [{:route-id     :route/article
        :path         \"/articles/:slug\"
        :resources    [{:resource :article/by-slug :blocking? true
                        :live {:freshness :fresh …} …} …]
        :blocking     [:article/by-slug]   ; SSR wait points
        :non-blocking [:comments/list]
        :ssr-wait?    true                  ; route has ≥1 blocking resource
        :current?     true                  ; this is the active route (live)
        :nav-token    <token>               ; the active nav-token (live)
        :blocking-live [:article/by-slug]}  ; blocking resources still unsettled
       …]

  Routes WITHOUT `:resources` are omitted (the graph is the resource view
  of the route table).

  ## Live state join (rf2-m5u3gt)

  EP-0003 asks the route graph to surface the CURRENT route/nav-token,
  active work, and fresh/stale state — not just static declarations. The
  optional `live` map joins the runtime state the panel reads decoupled:

      {:instance-rows  <projected live instances>
       :work-rows      <projected work-ledger rows>
       :current        {:route-id <route-id> :nav-token <token>}   ; routing slice
       :blocking-keys  [<scoped-key> …]}  ; the live unsettled-blocking set
                                          ;   SCOPED to the current route's
                                          ;   nav-token (rf2-cduftx F2) — the
                                          ;   caller passes
                                          ;   `(routing-blocking-keys slice
                                          ;     (:nav-token current))`, not the
                                          ;   all-token flatten, so an older
                                          ;   token's wait point cannot bleed
                                          ;   onto the active route.

  Each resource node gains a `:live` freshness rollup; the active route's
  node is flagged `:current? true` with its `:nav-token`, and its
  `:blocking-live` lists the declared blocking resources whose scoped keys
  are still in the live unsettled-blocking set (the SSR/route wait points
  that have NOT yet settled for the current nav-token). The bare
  `(project-route-graph routes-map)` arity stays a pure STATIC projection
  (nil live inputs ⇒ `:live` rollups are `:none`, no `:current?` flag) so
  the SSR/JVM path and existing callers are unchanged. Per Spec 016 §Route
  integration / §Xray and AI tooling."
  ([routes-map] (project-route-graph routes-map nil))
  ([routes-map {:keys [instance-rows work-rows current blocking-keys]}]
   (let [current-route-id (:route-id current)
         nav-token        (:nav-token current)
         blocking-key-rid (into #{}
                                (keep (fn [k]
                                        (when (and (vector? k) (= 3 (count k)))
                                          (second k))))
                                blocking-keys)]
     (->> (or routes-map {})
          (keep (fn [[route-id route-meta]]
                  (let [resources (:resources route-meta)]
                    (when (seq resources)
                      (let [nodes        (mapv #(route-resource-node % instance-rows work-rows)
                                               resources)
                            blocking     (into [] (comp (filter :blocking?) (map :resource)) nodes)
                            non-blocking (into [] (comp (remove :blocking?) (map :resource)) nodes)
                            current?     (and (some? current-route-id)
                                              (= route-id current-route-id))]
                        (cond-> {:route-id     route-id
                                 :path         (:path route-meta)
                                 :resources    nodes
                                 :blocking     blocking
                                 :non-blocking non-blocking
                                 :ssr-wait?    (boolean (seq blocking))}
                          current? (assoc :current?      true
                                          :nav-token     nav-token
                                          ;; the declared blocking resources whose
                                          ;; scoped key is still in the live
                                          ;; unsettled-blocking set for this nav-token
                                          :blocking-live (filterv blocking-key-rid blocking))))))))
          (sort-by (comp str :route-id))
          vec))))

;; ---------------------------------------------------------------------------
;; Lifecycle timeline + invalidation graph + cache-growth (from trace rows).
;; ---------------------------------------------------------------------------

(defn- trace-op
  "The `:operation` of a trace event, tolerating either the canonical
  `:operation` key or a bare `:op`."
  [ev]
  (or (:operation ev) (:op ev)))

(defn- trace-tags [ev] (or (:tags ev) {}))

(defn- trace-frame
  "The frame a `:rf.resource/*` / `:rf.mutation/*` trace event was emitted
  under — its `:rf.frame/id` tag, or nil when the event carries none.

  WHICH SPELLING, and why it is this one (rf2-l9vb09): the resources,
  machines and mutation families stamp the EP-0002 carried-frame stamp
  `:rf.frame/id` in `:tags`, which is the vocabulary every producer op this
  ns joins on emits. HTTP instead stamps the bare `[:tags :frame]`
  carve-out read by the contract-owned `re-frame.trace/trace-event-frame`;
  no op in this panel's families uses it, and reaching for that reader here
  would add a require edge for a fallback that can never fire.

  A nil frame is a legitimate value, not a failure: an event emitted outside
  any frame (or a hand-built fixture) simply joins with its own kind."
  [ev]
  (:rf.frame/id (trace-tags ev)))

(defn resource-projection-rows
  "Filter a trace buffer ONCE to the rows this panel's trace-borne projections
  can use (`resource-projection-op?`), oldest-first order preserved.

  The composite feeds every trace-borne projection from this ONE pass instead
  of handing each the whole buffer (rf2-y8doi.15). Each projection keeps its
  own filter, so passing it an unfiltered buffer is still correct — this only
  removes the repeated full-buffer scan. Pure; nil ⇒ `[]`."
  [trace-buffer]
  (filterv #(resource-projection-op? (trace-op %)) (or trace-buffer [])))

(def ^:private infinite-trace-ops
  "The four EP-0021 infinite-feed trace ops whose tags carry op-specific page
  evidence beyond the generic lifecycle fields (Spec 016 §Trace surfacing,
  Xray spec 024 §The `:rf.resource/*` trace family). The generic
  `lifecycle-timeline` projection drops the per-op facts; these ops get an
  extra `:page` detail map."
  #{:rf.resource/load-more
    :rf.resource/page-appended
    :rf.resource/page-failed
    :rf.resource/load-more-skipped})

(defn- page-detail
  "Project the EP-0021 infinite-feed page evidence carried in a load-more
  family trace event's `tags` into a compact `:page` detail map (Spec 016
  §Trace surfacing; Xray spec 024 rows for `:rf.resource/load-more` /
  `page-appended` / `page-failed` / `load-more-skipped`). Returns nil for a
  non-infinite op so the row carries no empty `:page` slot.

  Per op (only the keys the runtime actually stamps):
    - `:load-more`         → `:page-param` `:page-index` `:page-count`
    - `:page-appended`     → `:page-index` `:page-count` `:next-page-param`
                             `:terminal?`
    - `:load-more-skipped` → `:reason` (+ `:page-count` on the terminal arm)
    - `:page-failed`       → `:page-error` (the THIRD error channel)

  EGRESS (rf2-3tysyj / 3.4): the CURSOR-bearing facts (`:page-param` on
  load-more, `:next-page-param` on page-appended) and the `:page-error`
  envelope (an error value that may carry mutation data) are value-bearing —
  each routes through `eg` (the off-box `egress-value` walker on the off-box
  path; `identity` in-panel) BEFORE `summarize`, IDENTICALLY to the way
  `instance-row`'s `:cursor` and the generic `:cause` are projected. The
  METADATA (`:page-index` / `:page-count` / `:terminal?` / `:reason`) is not
  PII and rides raw. Only assoc's a key when the tag is present, so a
  partially-stamped row (e.g. a `load-more-skipped` no-feed arm) carries only
  the facts it has. Pure when `eg` is pure."
  [op tags eg]
  (when (infinite-trace-ops op)
    (cond-> {}
      (contains? tags :page-param)
      (assoc :page-param (summarize (eg (:page-param tags))))
      (contains? tags :next-page-param)
      (assoc :next-page-param (summarize (eg (:next-page-param tags))))
      (contains? tags :page-index)   (assoc :page-index (:page-index tags))
      (contains? tags :page-count)   (assoc :page-count (:page-count tags))
      (contains? tags :terminal?)    (assoc :terminal? (:terminal? tags))
      (contains? tags :reason)       (assoc :reason (:reason tags))
      (contains? tags :page-error)
      (assoc :page-error (summarize (eg (:page-error tags)))))))

(defn lifecycle-timeline
  "Project the resource trace rows in a trace buffer into a lifecycle
  TIMELINE — an ordered, render-safe row per `:rf.resource/*` event
  (Spec 016 §Xray and AI tooling — the lifecycle timeline). Each row:

      {:id          <trace-id>      ; stable per-process trace event id
       :operation   :rf.resource/fetch-started
       :label       \"fetch started\"
       :class       :lifecycle
       :resource-id :article/by-slug
       :resource/key <scoped-key-summary>  ; PRIVACY-summarized
       :generation  4
       :work-id     <id>
       :owner       <owner>
       :cause       <summary>        ; cause may carry data — summarized
       :status      {:before … :after …}
       :page        {…}}             ; EP-0021 — only on the four infinite ops

  The four EP-0021 infinite-feed ops (`:load-more` / `:page-appended` /
  `:page-failed` / `:load-more-skipped`) additionally carry a `:page`
  detail map with the op-specific page evidence (`:page-param` /
  `:next-page-param` cursor — egress-projected; `:page-index` /
  `:page-count` / `:terminal?` / `:reason` raw; `:page-error` summarized).
  See `page-detail`. A non-infinite op carries no `:page` slot.

  Pure over the trace vector; preserves buffer order (oldest-first). The
  panel renders this as the per-resource lifecycle strip; filtering by
  resource-id happens in the composite. Per Spec 016.

  Optional `egress-fn` (rf2-e0mq7a): `(fn [value] -> egressed)` applied to
  the VALUE-BEARING fields — the `:resource/key`'s scope/params (PII) and
  the `:cause` (may carry mutation data) — BEFORE `summarize`. An off-box
  (AI / log) caller threads the framework's
  wire-elision walker here so per-slot `:sensitive?` / `:large?`
  declarations are honoured on the trace-borne values, parallel to the way
  the live-cache projection routes its
  payloads through its own egress fn. The METADATA (operation, label,
  class, resource-id, generation, work-id, owner, status) is NOT a payload
  value and is never egressed — a redacted timeline STILL exposes the
  lifecycle shape. The in-panel caller omits the fn (the in-panel
  `summarize` is sufficient; values never leave the box). Pure when
  `egress-fn` is pure.

  Optional `sensitive-rids` (rf2-y8doi.15): the `sensitive-resource-ids` set
  from the static registry. A row whose resource-id is in it redacts its
  value-bearing slots ON BOX — the screen-share gate rf2-9zix0u closed for the
  live instance rows, reached here through the resource-id rather than a
  runtime-db path (see §ON-BOX sensitive-resource redaction above). Metadata
  is untouched, so a redacted row still shows its whole lifecycle shape."
  ([trace-buffer] (lifecycle-timeline trace-buffer nil nil))
  ([trace-buffer egress-fn] (lifecycle-timeline trace-buffer egress-fn nil))
  ([trace-buffer egress-fn sensitive-rids]
   (let [eg0 (or egress-fn identity)]
     (->> (or trace-buffer [])
          (filter #(resource-trace-op? (trace-op %)))
          (mapv (fn [ev]
                  (let [op   (trace-op ev)
                        tags (trace-tags ev)
                        rkey (:resource/key tags)
                        rid  (or (:resource-id tags)
                                 (when (vector? rkey) (second rkey)))
                        eg   (sensitive-eg eg0 (names-sensitive? sensitive-rids [rid]))]
                    (cond->
                      {:id           (:id ev)
                       :operation    op
                       :label        (op-label op)
                       :class        (op-class op)
                       :resource-id  rid
                       :resource/key (when rkey (scoped-key-summary rkey eg))
                       :generation   (:generation tags)
                       ;; Most resource-lifecycle ops carry the bare `:work/id`
                       ;; identity; the `:rf.resource/stale-suppressed` reply row
                       ;; carries the work identity ONLY as `:rf.reply/work-id`
                       ;; (rf2-o6c2jr — one name per fact on reply-envelope rows).
                       :work-id      (or (:work/id tags) (:rf.reply/work-id tags))
                       :owner        (:owner tags)
                       :cause        (when (contains? tags :cause)
                                       (summarize (eg (:cause tags))))
                       :status       {:before (:status-before tags)
                                      :after  (or (:status-after tags) (:status tags))}}
                      ;; EP-0021 — the four infinite-feed ops carry op-specific
                      ;; page evidence (cursor / page index / count / terminal /
                      ;; skip reason / page-error) the generic fields drop. A
                      ;; non-infinite op gets no `:page` slot (rf2-byl7bk.3.5).
                      (page-detail op tags eg)
                      (assoc :page (page-detail op tags eg))))))))))

(defn invalidation-graph
  "Project the `:rf.resource/invalidated` trace rows into the
  invalidation / mutation graph (Spec 016 §Invalidation / §Xray — the
  invalidation/mutation graph). One row per invalidation event:

      {:id        <trace-id>
       :scope     <summary>          ; PRIVACY — scope summarized
       :tags      [<tag> …]          ; the invalidated tags (identity, not PII)
       :cause     <summary>          ; e.g. [:mutation :article/save id] — summarized
       :matched   [<scoped-key-summary> …]  ; entries hit
       :match-count N
       :refetched N}                 ; active-owner entries refetched

  Distinguishes a broad-tag storm (high `:match-count`) without flooding;
  a zero-match invalidation surfaces `:match-count 0` so 'no match in
  this scope' is visible. Pure over the trace vector. Per Spec 016.

  Optional `egress-fn` (rf2-e0mq7a): `(fn [value] -> egressed)` applied to
  the VALUE-BEARING fields — `:scope` (PII), `:cause` (may carry mutation
  data), and each `:matched` scoped key's scope/params — BEFORE `summarize`.
  An off-box (AI / log) caller
  threads the wire-elision walker here so per-slot `:sensitive?` / `:large?`
  declarations are honoured on the trace-borne values, parallel to the
  live-cache projection. The non-PII metadata (`:tags` — invalidation
  identity, `:match-count`, `:refetched`) is NEVER egressed, so the
  tag-filter axis and the storm / zero-match distinction stay useful even
  on the default-redacted path. The in-panel caller omits the fn. Pure when
  `egress-fn` is pure.

  Optional `sensitive-rids` (rf2-y8doi.15): an invalidation row that MATCHED a
  key belonging to a `:sensitive?` resource redacts its value-bearing slots on
  box (`:scope`, `:cause`, every matched key's scope/params). The row is the
  unit because a broad-tag invalidation's scope and cause identify the same
  read its matched keys do. `:tags` / `:match-count` / `:refetched` are
  identity and counts, never redacted, so the storm and zero-match
  distinctions survive."
  ([trace-buffer] (invalidation-graph trace-buffer nil nil))
  ([trace-buffer egress-fn] (invalidation-graph trace-buffer egress-fn nil))
  ([trace-buffer egress-fn sensitive-rids]
   (let [eg0 (or egress-fn identity)]
     (->> (or trace-buffer [])
          (filter #(= :rf.resource/invalidated (trace-op %)))
          (mapv (fn [ev]
                  (let [tags    (trace-tags ev)
                        matched (or (:matched tags) [])
                        eg      (sensitive-eg
                                  eg0
                                  (names-sensitive? sensitive-rids
                                                    (map scoped-key-resource-id matched)))]
                    {:id          (:id ev)
                     :scope       (summarize (eg (:scope tags)))
                     :tags        (vec (:tags tags))
                     :cause       (when (contains? tags :cause)
                                    (summarize (eg (:cause tags))))
                     :matched     (mapv #(scoped-key-summary % eg) matched)
                     :match-count (count matched)
                     :refetched   (or (:refetched tags) 0)})))))))

;; ---------------------------------------------------------------------------
;; EP-0016 D3 — named scope-resolver resolution timeline (Spec 016 §Named
;; resource-scope resolvers / §Trace evidence). The runtime emits
;; `:rf.resource/scope-resolved` whenever a named resolver resolves a
;; `{:from-db …}` reference (route entry, event ensure, subscription read,
;; invalidation descriptor, the `resolve-resource-scope` resolver helper). Each
;; row explains WHICH resolver ran, the declared input NAMES that decided the
;; identity, the resolved scope (PRIVACY-summarized — a scope carries PII), and
;; whether the resolution FAILED CLOSED (`:resolved-nil?` — the scope-requiring
;; site got nil, never an implicit global).
;; ---------------------------------------------------------------------------

(defn scope-resolutions
  "Project the `:rf.resource/scope-resolved` trace rows into the named-scope
  resolution timeline (EP-0016 D3 / Spec 016 §Named resource-scope resolvers).
  One row per resolution event:

      {:id           <trace-id>
       :scope-id     :realworld/session   ; the named resolver that ran
       :inputs       [:username]          ; declared input NAMES (not values)
       :input-values <summary>            ; PRIVACY — resolved input values summarized
       :whole-db?    false                ; the explicit-cost fn-sugar flag
       :scope        <summary>            ; PRIVACY — the resolved scope summarized
       :resolved-nil? false}              ; true ⇒ FAIL-CLOSED (no implicit global)

  PRIVACY: the resolved input VALUES and the resolved SCOPE both carry PII
  (user/tenant/locale/impersonation ids), so both go through `summarize`; the
  declared input NAMES and the `:scope-id` are structural keywords (never PII).
  A `:resolved-nil? true` row is the fail-closed evidence — the resolver
  returned nil and the scope-requiring site produced NO global fallback. Pure
  over the trace vector; preserves buffer order (oldest-first). Per Spec 016."
  [trace-buffer]
  (->> (or trace-buffer [])
       (filter #(= :rf.resource/scope-resolved (trace-op %)))
       (mapv (fn [ev]
               (let [tags (trace-tags ev)]
                 {:id            (:id ev)
                  :scope-id      (or (:resource-id tags) (:scope-id tags))
                  :inputs        (vec (:inputs tags))
                  :input-values  (when (contains? tags :input-values)
                                   (summarize (:input-values tags)))
                  :whole-db?     (boolean (:whole-db? tags))
                  :scope         (summarize (:scope tags))
                  :resolved-nil? (boolean (:resolved-nil? tags))})))))

;; ---------------------------------------------------------------------------
;; EP-0016 D1/D2 — mutation continuation + descriptor-level invalidation
;; evidence (Spec 016 §Mutation completion continuations / §Trace evidence for
;; invalidation / §Phase order). The descriptor evidence rides ADDITIVELY on
;; the existing `:rf.mutation/succeeded` / `:rf.mutation/failed` settlement op
;; under `:invalidation` (resolved scope PER descriptor + the fail-closed
;; `:unresolved` `{:from-db …}` ids + the Rider-1 `:populate-exempt` keys); the
;; call-site `:reply-to` continuation dispatch is the `:rf.mutation/replied`
;; op. These two surfaces answer the operator's EP-0016 questions: "did one
;; write invalidate global AND session scopes precisely?" and "did the accepted
;; reply continue into app workflow, after the cache consequences settled?"
;; ---------------------------------------------------------------------------

(def ^:private mutation-settlement-ops
  "The `:rf.mutation/*` settlement ops that carry the descriptor-level
  `:invalidation` plan-trace (success + failure phases). A mutation `:succeeded`
  / `:failed` row carries `:invalidation` only when the mutation declared
  `:invalidates`."
  #{:rf.mutation/succeeded :rf.mutation/failed})

(defn- descriptor-summary
  "Summarize ONE resolved invalidation descriptor `{:scope … :cross-scope? …
  :tags … :refetch-populated? … :exempt-keys …}` (the per-descriptor plan-trace
  facet, Spec 016 §Trace evidence for invalidation) for a render-safe row.
  PRIVACY: the resolved `:scope` carries PII → summarized; the tags are
  identity, not PII. `:cross-scope?` marks the audited scope-agnostic escape
  (privacy-relevant per EP-0015); `:refetch-populated?` marks the partial-reply
  opt-in to a same-mutation refetch of a populated key. `:exempt-keys` is the
  truthful per-descriptor evidence (rf2-fi6tda.3 finding 2): the populated keys
  THIS descriptor's pass spared (empty when it opted into `:refetch-populated?
  true`). Surfacing it per-descriptor — not just the collapsed top-level
  `:populate-exempt` union — keeps a MIXED plan (one descriptor opts in, another
  default descriptor matches the same populated key) debuggable: the row shows
  exactly which pass spared which populated key. Each exempt scoped key carries
  PII in its scope/params → summarized.

  `eg` (rf2-y8doi.15) is the ROW's on-box value gate — `identity` by default,
  the redacting fn when the settled mutation touched a `:sensitive?` resource."
  ([descriptor] (descriptor-summary descriptor identity))
  ([{:keys [scope cross-scope? tags refetch-populated? exempt-keys]} eg]
   {:scope              (summarize (eg scope))
    :cross-scope?       (boolean cross-scope?)
    :tags               (vec tags)
    :refetch-populated? (boolean refetch-populated?)
    :exempt-keys        (mapv #(scoped-key-summary % eg) exempt-keys)}))

(defn mutation-invalidation-evidence
  "Project the descriptor-level invalidation evidence off the
  `:rf.mutation/succeeded` / `:rf.mutation/failed` settlement traces (EP-0016
  D2 / Spec 016 §Trace evidence for invalidation). One row per settled mutation
  that declared `:invalidates`:

      {:id              <trace-id>
       :operation       :rf.mutation/succeeded
       :mutation        :realworld/favorite-article
       :instance        [:favorite \"welcome\"]
       :descriptor-count 2
       :dispatched      [{:scope <summary> :cross-scope? false
                          :tags [[:article-list] [:article \"welcome\"]]
                          :refetch-populated? false
                          :exempt-keys [<scoped-key-summary> …]}
                         {:scope <summary> :cross-scope? false
                          :tags [[:feed]] :refetch-populated? false
                          :exempt-keys []}]
       :unresolved      [:realworld/session]   ; {:from-db …} refs that resolved nil
       :populate-exempt [<scoped-key> …]}      ; Rider-1 keys exempt from refetch (union)

  Distinguishes the precise-multi-scope case (≥2 dispatched descriptors with
  different scopes — the favorite/unfavorite global+session shape) from a
  fail-closed `:unresolved` descriptor (a `{:from-db …}` reference that
  resolved nil and produced NO invalidation, never an implicit global blast).
  Each `:dispatched` descriptor carries its OWN `:exempt-keys` — the truthful
  per-pass evidence (rf2-fi6tda.3 finding 2) a MIXED `:refetch-populated?` plan
  needs (one descriptor opts in and spares nothing while a default descriptor
  spares the populated key it matched). The top-level `:populate-exempt` is the
  union of those per-descriptor exempt sets. PRIVACY: each descriptor's resolved
  scope is summarized; tags are identity; exempt scoped keys are summarized.
  Pure over the trace vector. Per Spec 016.

  Optional `sensitive-rids` (rf2-y8doi.15): a settlement row any of whose
  exempt scoped keys names a `:sensitive?` resource redacts every descriptor's
  resolved `:scope` and every exempt key on box. `:unresolved` /
  `:descriptor-count` / `:tags` are identity and counts, never redacted."
  ([trace-buffer] (mutation-invalidation-evidence trace-buffer nil))
  ([trace-buffer sensitive-rids]
   (->> (or trace-buffer [])
        (keep (fn [ev]
                (let [op   (trace-op ev)
                      tags (trace-tags ev)
                      inv  (:invalidation tags)]
                  (when (and (contains? mutation-settlement-ops op) (map? inv))
                    (let [exempt-rids (->> (:dispatched inv)
                                           (mapcat :exempt-keys)
                                           (concat (:populate-exempt inv))
                                           (map scoped-key-resource-id))
                          eg          (sensitive-eg
                                        identity
                                        (names-sensitive? sensitive-rids exempt-rids))]
                      {:id               (:id ev)
                       :operation        op
                       :mutation         (:mutation tags)
                       :instance         (:instance tags)
                       :descriptor-count (:descriptor-count inv)
                       :dispatched       (mapv #(descriptor-summary % eg) (:dispatched inv))
                       :unresolved       (vec (:unresolved inv))
                       :populate-exempt  (mapv #(scoped-key-summary % eg)
                                               (:populate-exempt inv))})))))
        vec)))

(defn mutation-continuations
  "Project the call-site `:reply-to` continuation dispatch evidence off the
  `:rf.mutation/replied` trace rows (EP-0016 D1 / Spec 016 §Mutation completion
  continuations / §Phase order). The runtime emits `:rf.mutation/replied` ONLY
  when it dispatches a continuation to a call-site `:reply-to` target for an
  ACCEPTED terminal reply — a stale / superseded reply never fires one (that is
  a `:rf.mutation/stale-suppressed` row instead), so a row here is positive
  evidence the accepted reply continued into app workflow AFTER the cache
  consequences and instance settlement (phase 6 of the deterministic order).
  One row per continuation:

      {:id        <trace-id>
       :mutation  :realworld/save-article
       :instance  [:editor/save \"first-post\"]
       :work-id   [:rf.work/resource [:rf.mutation [:editor/save \"first-post\"]] 8]
       :status    :ok                     ; the accepted reply status
       :target    [:editor/save-replied]  ; the call-site :reply-to event target
       :cause     <summary>}              ; [:mutation <id> <instance>] — summarized

  PRIVACY: the `:cause` may carry data → summarized; the `:target` is a public
  event-vector prefix (data-only — the reply substrate rejects host handles),
  the mutation/instance/work/status are framework bookkeeping. Pure over the
  trace vector; preserves buffer order. Per Spec 016."
  [trace-buffer]
  (->> (or trace-buffer [])
       (filter #(= :rf.mutation/replied (trace-op %)))
       (mapv (fn [ev]
               (let [tags (trace-tags ev)]
                 {:id       (:id ev)
                  :mutation (:mutation tags)
                  :instance (:instance tags)
                  :work-id  (:work/id tags)
                  :status   (:status tags)
                  :target   (:target tags)
                  :cause    (when (contains? tags :cause) (summarize (:cause tags)))})))))

;; ---------------------------------------------------------------------------
;; EP-0019 — optimistic mutation lifecycle (apply / rolled-back / reconciled).
;; Spec 016 §Optimistic mutations / §Surfacing to tooling; Xray spec 024
;; §The optimistic-mutation lifecycle. The runtime (mutation_events.cljc)
;; applies a FORWARD optimistic patch BEFORE the request settles (phase 1.5)
;; and records the truthful per-entry INVERSE (snapshot + observed `:revision`)
;; on the mutation instance row. On reply settlement the recorded inverse is
;; deterministically disposed:
;;
;;   - SUCCESS    -> `:rf.mutation/optimistic-reconciled` — the authoritative
;;     `:populates` / `:patches` COMMITTED over the optimistic value; the
;;     recorded inverse was discarded (the commit superseded it).
;;   - FAILURE / accepted cancel / restore-DANGLE
;;     -> `:rf.mutation/optimistic-rolled-back` — conflict-aware per-key
;;     rollback: an UNMOVED `:revision` restores the recorded `:before`
;;     verbatim (`:restored true`); a MOVED `:revision` defers to
;;     `:on-conflict` (`:invalidate` default — mark stale + refetch, so
;;     `:restored false`; `:force` — restore the stale inverse anyway, the
;;     single-writer last-write-wins escape that also fires
;;     `:rf.warning/optimistic-force-clobber`).
;;   - STALE / superseded reply -> NEITHER (the inverse is discarded; never
;;     replayed — handled by the stale-suppression branch, no op here).
;;
;; The developer's bug-class question is "I saw an optimistic apply — did it
;; COMMIT or ROLL BACK, and was there a conflict?" The projections below pair
;; each `:applied` with its terminal disposition (by `:snapshot-id`) so an
;; apply still in flight reads `:pending`, a committed one `:reconciled`, and a
;; rolled-back one `:rolled-back` (with per-key restored-vs-conflict evidence +
;; the resolved `:on-conflict` rule). PRIVACY: a `:cause` may carry mutation
;; data → summarized; the scoped keys carry PII in scope/params → summarized;
;; the metadata (mutation id, instance, work id, generation, snapshot id,
;; on-conflict rule, restored/conflict booleans) is framework bookkeeping and
;; never egressed.
;; ---------------------------------------------------------------------------

(def optimistic-apply-op
  "The op the runtime emits for a forward optimistic apply (phase 1.5), BEFORE
  the request settles. Per Spec 016 §Optimistic mutations."
  :rf.mutation/optimistic-applied)

(def optimistic-reconcile-op
  "The op the runtime emits when a mutation SUCCEEDS and the authoritative
  write COMMITTED over the optimistic value (the recorded inverse discarded).
  Per Spec 016 §Optimistic settle."
  :rf.mutation/optimistic-reconciled)

(def optimistic-rollback-op
  "The op the runtime emits when a mutation FAILS / is cancelled / restore-
  dangles and the recorded inverse is rolled back (conflict-aware). Per Spec
  016 §Optimistic settle."
  :rf.mutation/optimistic-rolled-back)

(def optimistic-force-clobber-op
  "The `:warning`-level op the runtime emits when an `:on-conflict :force`
  rollback restored a now-stale inverse OVER a concurrent authoritative write
  (the deliberate single-writer last-write-wins escape — loud so an unexpected
  clobber is visible). Per Spec 016 §Optimistic settle / §On-conflict."
  :rf.warning/optimistic-force-clobber)

(def ^:private optimistic-ops
  "The closed `:rf.mutation/optimistic-*` lifecycle op set (apply + the two
  terminal settle dispositions). The force-clobber WARNING is surfaced
  separately (it rides a rollback) — it is not a lifecycle op."
  #{optimistic-apply-op optimistic-reconcile-op optimistic-rollback-op})

(defn optimistic-mutation-op?
  "True iff `operation` is a member of the EP-0019 optimistic-mutation
  lifecycle op set (`:applied` / `:reconciled` / `:rolled-back`)."
  [operation]
  (contains? optimistic-ops operation))

(def mutation-stale-suppressed-op
  "The op the runtime emits when a mutation reply arrives for a SUPERSEDED
  instance generation. Per Spec 016 §Optimistic mutations, such a reply
  settles nothing: \"its inverse is discarded, never replayed\", so it emits
  NEITHER `:rf.mutation/optimistic-reconciled` nor `…/optimistic-rolled-back`.
  It is the third terminal fact of an optimistic apply's life, and the only
  record that one exists."
  :rf.mutation/stale-suppressed)

(defn- optimistic-supersession-index
  "PURE: index the `:rf.mutation/stale-suppressed` rows by the identity an
  `:applied` row can be joined on (rf2-y8doi.15).

  A superseded mutation reply emits neither settle op, so `optimistic-lifecycle`
  paired its `:applied` row with nothing and reported `:pending` — \"still in
  flight\" for a request that has SETTLED and will never reconcile. That is the
  first bug class a network inspector exists to catch, and the evidence is
  already in the same buffer.

  Both ops carry the mutation work identity, so the join is EXACT rather than
  instance-wide: the suppression row's `:rf.reply/work-id` (one name per fact —
  the bare `:work/id` duplicate was dropped) equals the apply row's `:work/id`,
  and both carry `:instance` + `:generation` from the same execution. Indexed
  under BOTH `[:work <frame> <instance> <work-id>]` and `[:gen <frame>
  <instance> <generation>]` so a row missing one identity still joins on the
  other; an instance-only key is deliberately NOT indexed, because a second
  apply for the same instance genuinely in flight would then be mislabelled
  superseded.

  THE FRAME IS PART OF THE KEY (rf2-qqi7u), because every other element of it
  is FRAME-LOCAL. The runtime says so of the work-id outright — its scoped key
  and generation \"carry no frame identity, so two frames issuing the same
  resource at the same generation mint the SAME work-id\" — and a mutation
  instance id is either the caller's own value (a form keyed by a row id, which
  two frames can trivially share) or one derived from the mutation id and the
  frame's own monotone generation. The panel feeds this index ONE deliberately
  cross-frame buffer, so without the frame a suppression in frame B marked
  frame A's apply `:superseded` and handed it B's terminal, reporting a request
  that is still genuinely in flight as settled — the inverse of the bug class
  this outcome was added to catch. Both producer ops stamp `:rf.frame/id`, so
  the frame is simply read off the event rather than reconstructed.

  Each value is `{:outcome :superseded :id <trace-id>}`."
  [trace-buffer]
  (reduce (fn [acc ev]
            (if (= mutation-stale-suppressed-op (trace-op ev))
              (let [tags     (trace-tags ev)
                    frame    (trace-frame ev)
                    instance (:instance tags)
                    work-id  (:rf.reply/work-id tags)
                    gen      (:generation tags)
                    row      {:outcome :superseded :id (:id ev)}]
                (cond-> acc
                  (some? work-id) (assoc [:work frame instance work-id] row)
                  (some? gen)     (assoc [:gen frame instance gen] row)))
              acc))
          {} (or trace-buffer [])))

(defn- optimistic-settlement-index
  "PURE: index the terminal settle dispositions (`:reconciled` / `:rolled-back`)
  by `[<frame> <snapshot-id>]`, so an `:applied` row can be paired with its
  outcome. Each value is `{:outcome (:reconciled|:rolled-back) :id <trace-id>}`
  (the LAST terminal seen for a key wins, though a snapshot settles exactly once
  in practice). Apply rows with no terminal settle and no supersession row are
  left `:pending` — see `optimistic-supersession-index` for the third case.

  THE FRAME IS PART OF THE KEY for the same reason it is part of the
  supersession index's (rf2-qqi7u), and the reason is easy to miss here because
  a `:snapshot-id` LOOKS opaque enough to be unique. It is not: the runtime
  derives it from the mutation instance id plus the generation and nothing else,
  both of them frame-local, so it INHERITS that collision rather than escaping
  it. Two frames settling the same instance at the same generation therefore
  mint the same `:snapshot-id`, and on this panel's deliberately cross-frame
  buffer frame B's reconcile settled frame A's apply."
  [trace-buffer]
  (reduce (fn [acc ev]
            (let [op (trace-op ev)]
              (if (or (= op optimistic-reconcile-op) (= op optimistic-rollback-op))
                (let [sid (:snapshot-id (trace-tags ev))]
                  (cond-> acc
                    (some? sid)
                    (assoc [(trace-frame ev) sid]
                           {:outcome (if (= op optimistic-reconcile-op)
                                       :reconciled :rolled-back)
                            :id      (:id ev)})))
                acc)))
          {} (or trace-buffer [])))

(defn- rollback-disposition-summary
  "Summarize ONE `:rf.mutation/optimistic-rolled-back` per-key disposition
  `{:resource/key … :restored (bool) :conflict (bool) :on-conflict …}` for a
  render-safe row. PRIVACY: the scoped key carries PII in scope/params →
  summarized; `:restored` / `:conflict` / `:on-conflict` are bookkeeping.

  `eg` (rf2-y8doi.15) is the row's on-box value gate — `identity` by default."
  ([disposition] (rollback-disposition-summary disposition identity))
  ([{scoped-key :resource/key :keys [restored conflict on-conflict]} eg]
   (cond-> {:resource/key (scoped-key-summary scoped-key eg)
            :restored     (boolean restored)
            :conflict     (boolean conflict)}
     (some? on-conflict) (assoc :on-conflict on-conflict))))

(defn optimistic-lifecycle
  "Project the EP-0019 optimistic-mutation lifecycle from the trace buffer
  (Spec 016 §Optimistic mutations / §Surfacing to tooling). One row per
  `:rf.mutation/optimistic-applied`, PAIRED with its terminal settle
  disposition by `:snapshot-id`:

      {:id              <trace-id>      ; the :applied event's id
       :snapshot-id     <id>            ; the apply/settle correlation key
       :mutation        :realworld/favorite-article
       :instance        [:favorite \"welcome\"]
       :work-id         <work-id>
       :generation      8
       :scope           <summary>       ; PRIVACY — the mutation's resolved scope
       :affected-keys   [<scoped-key-summary> …]   ; the optimistically-patched keys
       :forward         [{:resource/key <summary> :revision N :forward :patch} …]
       :tag-matched-keys [<scoped-key-summary> …]  ; keys reached via :optimistic-tags
       :target-unresolved [:realworld/tenant]      ; fail-closed {:from-db …} ids
       :cause           <summary>
       :outcome         :pending   ; :pending / :reconciled / :rolled-back / :superseded
       :settled-id      <trace-id>        ; the terminal settle event's id (when settled)
       ;; ON RECONCILE (commit):
       :committed       [<scoped-key-summary> …]   ; keys the authoritative write owned
       :reconciliation-refetches [<scoped-key-summary> …]
       ;; ON ROLLBACK:
       :on-conflict     :invalidate       ; the resolved conflict rule
       :restored        [<scoped-key-summary> …]   ; keys restored verbatim
       :conflicted      [<scoped-key-summary> …]   ; keys whose :revision MOVED
       :refetched       [<scoped-key-summary> …]   ; keys deferred to the read path
       :dispositions    [{:resource/key <summary> :restored true :conflict false} …]}

  `:outcome` is the headline: an apply with no matching terminal settle is
  `:pending` (still in flight — the optimistic value is live on the cache); a
  COMMITTED one is `:reconciled`; a rolled-back one is `:rolled-back` (carrying
  the per-key restored-vs-conflict evidence + the resolved `:on-conflict`
  rule); and a SUPERSEDED one is `:superseded` — its reply arrived for an
  already-stale generation, so the runtime emitted `:rf.mutation/stale-
  suppressed` and no settle op at all, discarding the inverse rather than
  replaying it (Spec 016 §Optimistic settle). Before rf2-y8doi.15 that last
  case read `:pending` for ever, claiming a settled request was still in
  flight; the suppression row was already in the same buffer and is joined by
  the mutation work identity (see `optimistic-supersession-index`).
  A `:superseded` row carries `:settled-id` — the suppression event's id — and
  none of the reconcile / rollback facets, because none were emitted.

  The settle facets (`:committed` / `:restored` / `:conflicted` / …)
  are read off the terminal event's tags and attached to the apply row, so a
  developer reads the WHOLE lifecycle of one optimistic apply in a single row.
  Pure over the trace vector; preserves apply order (oldest-first). Per Spec
  016.

  Optional `sensitive-rids` (rf2-y8doi.15): an apply row any of whose scoped
  keys names a `:sensitive?` resource redacts its value-bearing slots on box
  (`:scope`, `:cause`, and every scoped key it renders). The counts, outcome,
  mutation/instance identity and `:target-unresolved` ids are never redacted."
  ([trace-buffer] (optimistic-lifecycle trace-buffer nil))
  ([trace-buffer sensitive-rids]
  (let [buffer    (or trace-buffer [])
        settle-ix (optimistic-settlement-index buffer)
        super-ix  (optimistic-supersession-index buffer)
        ;; index the terminal settle EVENTS by [frame snapshot-id] so the apply
        ;; row can pull the matching facets (committed / restored / … ). Keyed
        ;; exactly as `optimistic-settlement-index` is and for the same reason
        ;; (rf2-qqi7u); a bare snapshot-id let a FOREIGN frame's settle lend this
        ;; apply the keys IT committed, which is the worse half of the collision
        ;; — a wrong `:outcome` at least reads as a settlement, where a borrowed
        ;; `:committed` list reads as this mutation's own evidence.
        terminal-by-key
        (reduce (fn [acc ev]
                  (let [op (trace-op ev)]
                    (if (or (= op optimistic-reconcile-op) (= op optimistic-rollback-op))
                      (let [sid (:snapshot-id (trace-tags ev))]
                        (cond-> acc (some? sid) (assoc [(trace-frame ev) sid] ev)))
                      acc)))
                {} buffer)]
    (->> buffer
         (filter #(= optimistic-apply-op (trace-op %)))
         (mapv (fn [ev]
                 (let [tags     (trace-tags ev)
                       sid      (:snapshot-id tags)
                       instance (:instance tags)
                       ;; Every lookup below is scoped to THIS apply's own frame
                       ;; (rf2-qqi7u). The buffer is deliberately cross-frame and
                       ;; each of instance / work-id / generation / snapshot-id
                       ;; is frame-local, so the frame is what makes an otherwise
                       ;; exact-looking join actually exact.
                       frame    (trace-frame ev)
                       ;; A settle op wins over a supersession row: an apply
                       ;; that DID reconcile or roll back has its own terminal,
                       ;; and a later reply for the same instance being
                       ;; suppressed says nothing about it.
                       settled  (or (get settle-ix [frame sid])
                                    (get super-ix [:work frame instance (:work/id tags)])
                                    (get super-ix [:gen frame instance (:generation tags)]))
                       outcome  (:outcome settled :pending)
                       term-ev  (get terminal-by-key [frame sid])
                       term     (trace-tags term-ev)
                       eg       (sensitive-eg
                                  identity
                                  (names-sensitive?
                                    sensitive-rids
                                    (map scoped-key-resource-id
                                         (concat (:affected-keys tags)
                                                 (:tag-matched-keys tags)
                                                 (map :resource/key (:revisions tags))
                                                 (:committed term)
                                                 (:restored term)
                                                 (:conflicted term)
                                                 (:refetched term)))))]
                   (cond-> {:id                (:id ev)
                            :snapshot-id       sid
                            :mutation          (:mutation tags)
                            :instance          instance
                            :work-id           (:work/id tags)
                            :generation        (:generation tags)
                            :scope             (summarize (eg (:scope tags)))
                            :affected-keys     (mapv #(scoped-key-summary % eg) (:affected-keys tags))
                            :forward           (mapv (fn [{scoped-key :resource/key :keys [revision forward]}]
                                                       {:resource/key (scoped-key-summary scoped-key eg)
                                                        :revision     revision
                                                        :forward      forward})
                                                     (:revisions tags))
                            :tag-matched-keys  (mapv #(scoped-key-summary % eg) (:tag-matched-keys tags))
                            :target-unresolved (vec (:target-unresolved tags))
                            :cause             (when (contains? tags :cause)
                                                 (summarize (eg (:cause tags))))
                            :outcome           outcome}
                     (some? settled)
                     (assoc :settled-id (:id settled))
                     (= :reconciled outcome)
                     (assoc :committed (mapv #(scoped-key-summary % eg) (:committed term))
                            :reconciliation-refetches
                            (mapv #(scoped-key-summary % eg) (:reconciliation-refetches term)))
                     (= :rolled-back outcome)
                     (assoc :on-conflict  (:on-conflict term)
                            :restored     (mapv #(scoped-key-summary % eg) (:restored term))
                            :conflicted   (mapv #(scoped-key-summary % eg) (:conflicted term))
                            :refetched    (mapv #(scoped-key-summary % eg) (:refetched term))
                            :dispositions (mapv #(rollback-disposition-summary % eg)
                                                (:dispositions term)))))))
         vec))))

(defn optimistic-force-clobbers
  "Project the `:rf.warning/optimistic-force-clobber` trace rows (EP-0019
  §On-conflict). The runtime emits ONE per rollback where `:on-conflict :force`
  restored a now-stale inverse OVER a concurrent authoritative write (the
  deliberate single-writer last-write-wins escape — surfaced LOUD so an
  unexpected clobber is visible). One row per warning:

      {:id          <trace-id>
       :mutation    :realworld/favorite-article
       :instance    [:favorite \"welcome\"]
       :forced-keys [<scoped-key-summary> …]   ; the conflicted keys force-clobbered
       :recovery    :review-on-conflict
       :reason      \"mutation … rolled back with :on-conflict :force …\"}

  PRIVACY: the forced scoped keys carry PII in scope/params → summarized; the
  mutation/instance/recovery/reason are bookkeeping. Pure over the trace
  vector; preserves order. Per Spec 016.

  Optional `sensitive-rids` (rf2-y8doi.15): a warning whose forced keys name a
  `:sensitive?` resource redacts those keys' scope/params on box. The warning
  itself — mutation, instance, count, recovery and reason — always renders, so
  a clobber on a sensitive resource stays exactly as LOUD as any other."
  ([trace-buffer] (optimistic-force-clobbers trace-buffer nil))
  ([trace-buffer sensitive-rids]
   (->> (or trace-buffer [])
        (filter #(= optimistic-force-clobber-op (trace-op %)))
        (mapv (fn [ev]
                (let [tags   (trace-tags ev)
                      forced (:forced-keys tags)
                      eg     (sensitive-eg
                               identity
                               (names-sensitive? sensitive-rids
                                                 (map scoped-key-resource-id forced)))]
                  {:id          (:id ev)
                   :mutation    (:mutation tags)
                   :instance    (:instance tags)
                   :forced-keys (mapv #(scoped-key-summary % eg) forced)
                   :recovery    (:recovery tags)
                   :reason      (:reason tags)}))))))

(defn cache-growth
  "Project the live instance rows + the work ledger into the cache-growth
  view (Spec 016 §Paginated and previous data / §Xray — the cache-growth
  view). Aggregates per-resource-id:

      {:by-resource [{:resource-id :articles/list
                      :entry-count 12       ; cached entries (e.g. list pages)
                      :owned-count 1        ; entries with ≥1 active owner
                      :gc-eligible 11}      ; inactive → GC-eligible
                     …]
       :total-entries 14
       :total-gc-eligible 12
       :live-work 2}                        ; non-terminal ledger rows

  Surfaces unbounded list-param growth (many entries, few owners) so the
  operator sees a cache that is growing without owners pinning it. Pure
  over the projected instance + work rows. Per Spec 016."
  [instance-rows work-rows]
  (let [by-resource
        (->> instance-rows
             (group-by :resource-id)
             (mapv (fn [[rid rows]]
                     {:resource-id rid
                      :entry-count (count rows)
                      :owned-count (count (filter (comp pos? :owner-count) rows))
                      :gc-eligible (count (filter :gc-eligible? rows))}))
             (sort-by (comp - :entry-count))
             vec)]
    {:by-resource       by-resource
     :total-entries     (count instance-rows)
     :total-gc-eligible (count (filter :gc-eligible? instance-rows))
     :live-work         (count (remove :terminal? work-rows))}))

;; ---------------------------------------------------------------------------
;; Lints (Spec 016 §Xray and AI tooling — the lints + the audit list).
;; ---------------------------------------------------------------------------

(defn global-scope-audit
  "The STANDING scope audit surface (Spec 016 §Xray scope diagnostics):
  enumerate every `:rf.scope/global` resource — the structural
  security-review list that replaces the old `/me` heuristic. Returns the
  registry rows whose scope policy is the explicit-global claim. Pure
  over the projected registry rows."
  [registry-rows]
  (->> registry-rows
       (filter (comp :global? :scope))
       vec))

(def ^:private session-ish-tokens
  "Substrings that make an explicit-global request look session-dependent
  — the downgraded-to-defense-in-depth heuristic (Spec 016: warn about
  SUSPICIOUS explicit-global, not compensate for a missing scope)."
  ["/me" "/current-user" "/current_user" "current-session" "/profile"
   "/account" "impersonat"])

(defn suspicious-global-warnings
  "Defense-in-depth lint (Spec 016 §Xray scope diagnostics): from the
  `:rf.scope/global` audit list, flag any whose registered doc /
  resource-id LOOKS session-dependent (`/me`, `/current-user`, profile/
  account/impersonation hints). NOT the boundary (a missing scope is a
  loud runtime error now); a hint the operator should re-examine an
  explicit-global claim. Returns `[{:resource-id … :hint \"…\"}]`."
  [registry-rows]
  (->> (global-scope-audit registry-rows)
       (keep (fn [row]
               (let [hay (str/lower-case
                           (str (:resource-id row) " " (:doc row)))]
                 (when (some #(str/includes? hay %) session-ish-tokens)
                   {:resource-id (:resource-id row)
                    :hint        (str "explicit :rf.scope/global on a resource that "
                                      "looks session-dependent — confirm the data is "
                                      "identical for every user/tenant.")}))))
       vec))

(defn orphaned-owner-lint
  "Orphaned-owner lint (Spec 016 §Xray and AI tooling / §Release authority):
  an app-minted `[:dashboard/opened …]` (or other app-kind) owner pinning an entry
  with no observed `:rf.resource/owner-released` for that owner in the
  trace. Route / machine / ssr owners are framework-released (route on
  nav supersession, machine on actor destroy, ssr on request teardown),
  so only APP-kind owners are linted here.

  `instance-rows` supplies the live `:active-owners`; `trace-buffer`
  supplies the observed `:rf.resource/owner-released` events. Returns
  `[{:owner <owner> :resource-id R}]` for each app-kind owner still
  pinning an entry with no release seen. Pure."
  [instance-rows trace-buffer]
  (let [released (->> (or trace-buffer [])
                      (filter #(= :rf.resource/owner-released (trace-op %)))
                      (keep #(:owner (trace-tags %)))
                      (into #{}))
        app-kind? (fn [owner]
                    (and (vector? owner)
                         (not (contains? #{:route :machine :ssr} (first owner)))))]
    (->> instance-rows
         (mapcat (fn [row]
                   (for [owner (:active-owners row)
                         :when (and (app-kind? owner)
                                    (not (contains? released owner)))]
                     {:owner owner :resource-id (:resource-id row)})))
         distinct
         vec)))

(defn optimistic-reach-lint
  "Optimistic-reach lint (rf2-ynkzj): a settled optimistic mutation whose
  patch reached a cache key that its settlement never did. The canonical
  case is a favourite whose `:optimistic-tags` patch the viewer's feed while
  its `:invalidates` forgets the feed descriptor — the feed stays on the
  optimistic value, loaded and never marked stale, and nothing says so.

  A set difference over records the runtime already emits: the
  `:rf.mutation/optimistic-reconciled` `:optimistic-keys`, minus its
  `:committed` keys and `:reconciliation-refetches`, minus the
  `:affected-keys` on the `:rf.mutation/succeeded` settlement of the same
  FRAME, instance and work id (the union survives that settlement row having
  left the buffer, since both reconcile facets are subsets of it). The frame
  is part of that identity because instance and work id are both frame-local
  — rf2-qqi7u; see `optimistic-supersession-index` for the full reasoning.

  A key in a scope the write-side `:rf.warning/mutation-scope-mismatch`
  tripwire already named as `:other-scope` for the same mutation is left to
  that warning: a wrong-scope descriptor gets one diagnostic, not two.

  Informational and dedupe-keyed on `[mutation instance missing-keys]` (raw
  keys, not their summaries), so a settled instance seen twice is one row:

      [{:id           <reconciled trace id>
        :mutation     :realworld/favorite-article
        :instance     [:favorite \"welcome\"]
        :missing-keys [<scoped-key-summary> …]
        :hint         \"not reconciled — …\"}]

  Leaving a value optimistic can be a deliberate authoring choice, so the
  hint says what settlement did not reach rather than calling it a defect.
  PRIVACY: the missing scoped keys are summarized. Pure; preserves order.

  Optional `sensitive-rids` (rf2-y8doi.15): a row whose missing keys name a
  `:sensitive?` resource redacts those keys' scope/params on box. The hint
  names RESOURCE-IDS only (never a scope or params value), so it is left
  intact and the finding stays readable."
  ([trace-buffer] (optimistic-reach-lint trace-buffer nil))
  ([trace-buffer sensitive-rids]
  (let [buffer (or trace-buffer [])
        ;; Keyed by [frame instance work-id] (rf2-qqi7u) — the same correction
        ;; the two optimistic settlement indexes above carry, and needed here
        ;; for the same reason: instance and work-id are both frame-local, so
        ;; without the frame a settlement in ANOTHER frame answered for this
        ;; one and the finding simply disappeared. That is the reassuring
        ;; direction for a lint — silence reads as a clean panel.
        affected-by-work
        (reduce (fn [acc ev]
                  (if (= :rf.mutation/succeeded (trace-op ev))
                    (let [tags (trace-tags ev)]
                      (update acc [(trace-frame ev) (:instance tags) (:work/id tags)]
                              (fnil into #{}) (:affected-keys tags)))
                    acc))
                {} buffer)
        warned-scopes
        (into #{}
              (comp (filter #(= :rf.warning/mutation-scope-mismatch (trace-op %)))
                    (map trace-tags)
                    (map (juxt :mutation :other-scope)))
              buffer)
        candidates
        (keep (fn [ev]
                (let [{:keys [mutation instance] :as tags} (trace-tags ev)
                      reached (-> #{}
                                  (into (:committed tags))
                                  (into (:reconciliation-refetches tags))
                                  (into (get affected-by-work
                                             [(trace-frame ev) instance (:work/id tags)])))
                      missing (->> (:optimistic-keys tags)
                                   (remove reached)
                                   (remove #(and (vector? %)
                                                 (contains? warned-scopes [mutation (first %)])))
                                   distinct
                                   (sort-by pr-str)
                                   vec)]
                  (when (seq missing)
                    {:dedupe-key   [mutation instance missing]
                     :id           (:id ev)
                     :mutation     mutation
                     :instance     instance
                     :missing-keys (let [eg (sensitive-eg
                                              identity
                                              (names-sensitive?
                                                sensitive-rids
                                                (map scoped-key-resource-id missing)))]
                                     (mapv #(scoped-key-summary % eg) missing))
                     :hint         (str "not reconciled — the optimistic patch reached "
                                        (str/join ", " (map #(pr-str (when (vector? %) (second %)))
                                                            missing))
                                        " and this settlement did not populate, patch, remove or "
                                        "invalidate " (if (= 1 (count missing)) "it" "them")
                                        ", so the optimistic value stays until something reloads it.")})))
              (filter #(= optimistic-reconcile-op (trace-op %)) buffer))]
    (first
      (reduce (fn [[rows seen] {:keys [dedupe-key] :as row}]
                (if (contains? seen dedupe-key)
                  [rows seen]
                  [(conj rows (dissoc row :dedupe-key)) (conj seen dedupe-key)]))
              [[] #{}] candidates)))))

;; ---------------------------------------------------------------------------
;; Per-slot value egress contract (rf2-tgm1xu). Spec 016 §Xray, line 314:
;; "Params, scopes, and data carry :sensitive? / :large? classification
;; through the shared project-egress door; Xray sees REDACTED SUMMARIES,
;; NOT raw values."
;; ---------------------------------------------------------------------------
;;
;; A resource cache entry mixes two kinds of slot:
;;   - VALUE-bearing slots (`:data` / `:error` / `:refresh-error`) + the
;;     key's scope/params carry the remote payload, failure envelopes, and
;;     the caller identity — the only slots that can hold PII or a large
;;     blob, so the only ones routed through the off-box egress walker
;;     (a `:sensitive?` slot redacts, a `:large?` slot elides);
;;   - METADATA slots (`:status`, `:generation`, `:attempt`, `:request-id`,
;;     `:current-work`, `:active-owners`, `:tags`, `:loaded-at`, `:stale-at`,
;;     `:invalidated-at`, `:resource/id`) are runtime BOOKKEEPING — non-PII
;;     facts the operator needs to answer "is it stale, who owns it, what's
;;     the request id". They ALWAYS project, never routed through egress.
;;
;; `instance-row` takes an optional `egress-fn` `(fn [value slot-key key-id])`
;; and applies it to ONLY the value-bearing slots before `summarize`, keeping
;; the RAW scoped-key as the row identity. The PRIOR design routed the WHOLE
;; entry through `egress-runtime-db-value` BEFORE projection: with the
;; off-box runtime-db default the entry collapsed to the bare `:rf/redacted`
;; sentinel, so the projection read nil for status/owners/tags/request-id —
;; the default rows were USELESS and the metadata filters (which filter the
;; projected rows) could never match. Egressing the KEY ITSELF would also
;; collapse two entries whose scope/params redact to the same sentinel; the
;; row keeps the raw key as identity to avoid that.
