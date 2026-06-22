(ns re-frame.projection
  "Record-level egress projection — `project-egress` + the six ruled
  `:rf.egress/*` profiles, layered over `re-frame.elision/elide-wire-value`.

  EP-0015 §10 (Projection Profiles) + §11 (`elide-wire-value` stays the
  value primitive). Graduated normatively into
  [`spec/015-Data-Classification.md` §Projection]
  (../../../../../spec/015-Data-Classification.md).

  ## The three-layer model (Spec 015)

      Classification names facts.   (frame config / registration metadata)
      Projection applies them.      (THIS ns — `project-egress`)
      Sink policy routes records.   (frame `:observability`)

  Real egress surfaces emit RECORDS, not bare values: handled-event
  records, error records, epoch records, MCP snapshots, sub-cache reads,
  HTTP diagnostics, hydration payloads. `project-egress` is the public,
  record-level boundary primitive — the required step before any off-box
  sink. It knows which record slots are app-db-shaped / event-shaped /
  exception-shaped / HTTP-shaped / public-summary-only, applies the merged
  frame-owned classification (`re-frame.frame-classification`), and
  DELEGATES to `elide-wire-value` for every tree-shaped slot where frame
  policy applies. The per-record-kind projectors are PRIVATE (EP-0015
  issue 2, ruled: `project-egress` names the BOUNDARY, not a record kind;
  per-record-kind projectors stay private). There is no public
  `project-record`.

  ## Profiles (EP-0015 §10, ruled six-member CLOSED enum)

  The normal public choice at a boundary is *\"which boundary is this?\"* —
  a named egress profile under `:rf.egress/profile` — not *\"which
  combination of booleans did I remember?\"* The boolean `:rf.size/*` flags
  (`elide-wire-value`'s opt layer) remain the ADVANCED override beneath the
  profiles. A profile resolves to a `:rf.size/*` opt-set; an explicit
  `:rf.size/*` opt the caller also passes COMPOSES on top (the explicit
  override WINS — `profile-opts` is the floor, the caller's opts the
  overlay).

  The six ruled profiles (EP-0015 issue 3):

  | Profile | Default behaviour |
  |---|---|
  | `:rf.egress/off-box-observability` | hosted monitoring; redact sensitive, elide large, omit digests. |
  | `:rf.egress/off-box-tool` | MCP / AI / tool wire; redact sensitive, elide large, include structural indicators. |
  | `:rf.egress/local-redacted` | on-box dev UI default; suppress sensitive display. |
  | `:rf.egress/local-raw` | trusted local operator; include sensitive AND large. |
  | `:rf.egress/ssr-hydration` | projection AFTER the §14 allowlist; defence-in-depth. |
  | `:rf.egress/public-error` | client-safe error projection; never internal raw values. |

  ## Fail-closed (EP-0002 / Spec 015 §Direct reads)

  Egress policy is frame-scoped. A tree-shaped slot is projected only when
  the frame is KNOWN (the explicit `:frame` opt, the FRAME-BEARING record's
  own top-level `:frame` slot — seeded into opts when opts omits one, the
  explicit opt winning — or the carried-invariant scope). With no frame and
  no `:rf.size/include-sensitive? true` opt-out, the underlying
  `elide-wire-value` redacts the whole value to `:rf/redacted` rather than
  borrow another frame's policy — `project-egress` inherits that fail-closed
  posture, it does NOT synthesise `:rf/default`.

  ## Event-shaped slots carry REGISTRATION-owned marks (EP-0015)

  An `:rf.observe/*` record's `:event` slot is the raw dispatched vector — a
  REGISTRATION-OWNED transient payload (EP-0015): a handler registered with
  `reg-event {:sensitive [[:password]]}` declares which of ITS OWN event args
  are sensitive, independent of the frame's app-db classification. So the
  `:event` slot is projected through the event registration's `:sensitive` /
  `:large` marks (the always-on `:classification/redact-event-by-registration` hook)
  BEFORE the frame-policy size/sensitive walk — the same arg-map-rooted
  redaction the dev trace path applies. Without this a declared-sensitive event
  arg would ride raw to an off-box `:observability :errors` sink whenever the
  frame carried no matching classified `:sensitive` app-db declaration (the
  wrong owner). Sentinels the registration pass writes survive the
  subsequent frame-policy walk (a redacted leaf is inert)."
  (:require [re-frame.elision :as elision]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The six ruled `:rf.egress/*` profiles (EP-0015 §10, issue 3).
;;
;; Each profile resolves to a `:rf.size/*` opt-set — the boolean override
;; layer `elide-wire-value` already consumes. The set is a CLOSED enum;
;; additions require a recorded ruling (Spec 015 §The graduation gate).
;; ---------------------------------------------------------------------------

(def ^:const profiles
  "The closed six-member `:rf.egress/profile` vocabulary (EP-0015 §10)."
  #{:rf.egress/off-box-observability
    :rf.egress/off-box-tool
    :rf.egress/local-redacted
    :rf.egress/local-raw
    :rf.egress/ssr-hydration
    :rf.egress/public-error})

(defn unknown-egress-profile-ex
  "Build the canonical `:rf.error/unknown-egress-profile` thrown-error
  (Spec 009 §The thrown-error shape) for a `:rf.egress/profile` value
  outside the closed `profiles` enum. ONE source of truth for BOTH closed-
  enum guards — the in-file `resolve-elision-opts` guard
  (`:where 'rf/project-egress`) and the epoch boundary guard
  (`re-frame.epoch.tool-pair/resolve-egress-profile`,
  `:where 'epoch/projected-record`) — so the human sentence + the
  `[:rf.error/unknown-egress-profile]` token never drift between the two
  sites (rf2-krrv87). Each caller passes its own `where-sym`; the bad
  `profile` value + the valid `profiles` enum land in ex-data. Routes
  through `error/thrown-ex-info`, so the message LEADS with the human
  sentence and TRAILS with the greppability token. Returns the `ex-info`;
  the caller throws."
  [where-sym profile]
  (error/thrown-ex-info
    :rf.error/unknown-egress-profile
    where-sym
    (str "unknown :rf.egress/profile " profile
         " — the egress profile enum is closed; pass one of "
         (pr-str profiles) ".")
    {:recovery :use-a-known-profile
     :extra    {:profile profile
                :valid   profiles}}))

(def ^:private profile->size-opts
  "Resolve each profile to its default `:rf.size/*` opt-set (the §10
  default-behaviour table). All six off-box / on-box-redacted boundaries
  fail closed (`include-sensitive?`/`include-large?` both false, no
  digests); `:rf.egress/local-raw` is the single trusted-local boundary
  that opts sensitive AND large back in.

  `:rf.egress/off-box-tool` additionally turns on `:rf.size/include-digests?`
  so the marker carries the structural indicators / counters a tool needs
  to reason about shape without seeing content (the §10 \"include
  structural indicators\" clause)."
  {:rf.egress/off-box-observability
   {:rf.size/include-sensitive? false
    :rf.size/include-large?     false
    :rf.size/include-digests?   false}

   :rf.egress/off-box-tool
   {:rf.size/include-sensitive? false
    :rf.size/include-large?     false
    :rf.size/include-digests?   true}

   :rf.egress/local-redacted
   {:rf.size/include-sensitive? false
    :rf.size/include-large?     false
    :rf.size/include-digests?   false}

   :rf.egress/local-raw
   {:rf.size/include-sensitive? true
    :rf.size/include-large?     true
    :rf.size/include-digests?   false}

   :rf.egress/ssr-hydration
   {:rf.size/include-sensitive? false
    :rf.size/include-large?     false
    :rf.size/include-digests?   false}

   :rf.egress/public-error
   {:rf.size/include-sensitive? false
    :rf.size/include-large?     false
    :rf.size/include-digests?   false}})

(defn profile-size-opts
  "Return the `:rf.size/*` opt-set a profile resolves to, or `nil` for an
  unknown / absent profile. Public so a tool can introspect the resolution
  (Xray's egress-profile panel) without re-deriving the table."
  [profile]
  (get profile->size-opts profile))

;; ---------------------------------------------------------------------------
;; Opts resolution — profile floor + explicit `:rf.size/*` overlay.
;;
;; A profile resolves to a `:rf.size/*` opt-set; an explicit `:rf.size/*`
;; key the caller ALSO passes composes ON TOP (the override wins). The
;; non-size opts (`:frame`, `:path`, `:rf.size/threshold-bytes`,
;; `:as-of-epoch`) flow through to `elide-wire-value` untouched.
;; ---------------------------------------------------------------------------

(def ^:private size-override-keys
  "The `:rf.size/*` boolean keys a profile resolves but a caller may
  explicitly override (the override wins). `:rf.size/threshold-bytes` is
  NOT here — it is a pass-through tuning knob, not a profile-resolved
  boolean."
  [:rf.size/include-sensitive?
   :rf.size/include-large?
   :rf.size/include-digests?])

(defn resolve-elision-opts
  "Resolve an egress `opts` map to the `elide-wire-value` opts a
  tree-shaped slot is walked under.

  Composition (EP-0015 §10): the profile's `:rf.size/*` opt-set is the
  FLOOR; any `:rf.size/*` boolean the caller passes explicitly OVERLAYS it
  (the override wins). When no profile is given the opts pass through as-is
  (the advanced raw-flags path). `:frame` / `:path` /
  `:rf.size/threshold-bytes` / `:as-of-epoch` are preserved verbatim.

  An UNKNOWN `:rf.egress/profile` value throws — the enum is closed
  (Spec 015 §The graduation gate), so a typo is a loud error, never a
  silent fall-through to a permissive walk."
  [opts]
  (let [opts    (or opts {})
        profile (:rf.egress/profile opts)]
    (cond
      (nil? profile)
      ;; No profile — the advanced raw-flags path. Pass the size opts
      ;; through verbatim (minus the absent profile key).
      (dissoc opts :rf.egress/profile)

      (contains? profile->size-opts profile)
      (let [floor    (profile->size-opts profile)
            ;; Caller's explicit `:rf.size/*` booleans overlay the floor.
            explicit (select-keys opts size-override-keys)]
        (-> (dissoc opts :rf.egress/profile)
            (merge floor)
            (merge explicit)))

      :else
      (throw (unknown-egress-profile-ex 'rf/project-egress profile)))))

;; ---------------------------------------------------------------------------
;; Per-record-kind slot classification (EP-0015 §11) — PRIVATE.
;;
;; The record projector knows which slots of each `:rf.observe/*` record
;; kind are tree-shaped (frame policy applies — delegate to the walker),
;; event-shaped, public-summary-only, or omit-by-default. `project-egress`
;; dispatches on the record's `:kind`; an unknown / kindless record is
;; treated as a tree-shaped VALUE and walked whole (the direct-read path —
;; Spec 015 §Direct reads).
;; ---------------------------------------------------------------------------

(defn- walk-slot
  "Project one tree-shaped slot through `elide-wire-value` against the
  resolved elision opts (the profile-floor + override overlay), seeded at
  the record's frame. Delegates entirely to the walker — the size /
  sensitive policy is the frame's, NOT reimplemented here (EP-0015 §11)."
  [v elision-opts]
  (elision/elide-wire-value v elision-opts))

(defn- redact-event-by-registration
  "Apply the event handler's REGISTRATION-OWNED `:sensitive` / `:large`
  classification to an event-shaped slot value (a `[event-id arg-map …]` vector)
  via the always-on `:classification/redact-event-by-registration` hook
  (EP-0015). A no-op (returns `event` unchanged) when the hook is unbound or the
  handler declared none. This is the EVENT-owner pass that precedes the
  FRAME-policy `walk-slot`: event args are registration-owned (the handler's
  `:sensitive`), not app-db-owned (the frame's classification)."
  [event]
  (if-let [redact (late-bind/get-fn-cached :classification/redact-event-by-registration)]
    (redact event)
    event))

(defn- project-event-slot
  "Project an event-shaped slot: the event registration's marks FIRST (the
  EVENT owner — EP-0015), then the frame-policy size/sensitive
  walk. A sentinel the registration pass writes is inert under the subsequent
  walk."
  [event elision-opts]
  (-> event
      redact-event-by-registration
      (walk-slot elision-opts)))

;; ---- handled-event record (`:rf.observe/handled-event`) ------------------
;;
;; EP-0015 issue 4 (ruled, sharpened): the off-box default record omits the
;; `:event` ARGS slot entirely. It carries only summary fields — frame,
;; event id, status, elapsed, effect keys, work/correlation ids. Tools may
;; opt into a richer PROJECTED payload (never raw): a `:rf.egress/local-raw`
;; or any profile that opts `:rf.size/include-sensitive? true` keeps the
;; `:event` slot, projected through the walker.
;;
;; "Summary-only" slots (`:event-id`, `:status`, `:elapsed-ms`, `:effects`,
;; `:correlation`, `:kind`, `:frame`) are structural metadata, never raw
;; user values, so they pass through unchanged.

(def ^:private handled-event-summary-keys
  #{:kind :frame :event-id :status :elapsed-ms :effects :correlation})

(defn- include-event-args?
  "Whether the off-box-default-omitted `:event` args slot is retained. Only
  when the resolved walk explicitly opts sensitive content back in
  (trusted-local) — the off-box default fails closed and omits it."
  [elision-opts]
  (true? (:rf.size/include-sensitive? elision-opts)))

(defn- project-handled-event
  [record elision-opts]
  (let [base (select-keys record handled-event-summary-keys)]
    (if (and (contains? record :event)
             (include-event-args? elision-opts))
      ;; Trusted-local opt-in: keep `:event`, PROJECTED (never raw) — the event
      ;; registration's marks FIRST (EP-0015), then frame policy.
      (assoc base :event (project-event-slot (:event record) elision-opts))
      ;; Off-box default: the `:event` args slot is omitted entirely.
      base)))

;; ---- error record (`:rf.observe/error`) ----------------------------------
;;
;; The `:event` and `:tags` slots are tree-shaped (frame policy applies);
;; `:exception` is an opaque host value the walker cannot prove the
;; provenance of (Spec 015 §Author guidance for the exception-path
;; residual) — under `:rf.egress/public-error` it is dropped entirely (a
;; client-safe projection never carries internal raw values); otherwise it
;; is walked like any other tree slot (a non-tree exception object passes
;; through). Summary slots pass through.

(def ^:private error-summary-keys
  #{:kind :frame :error :event-id :status :elapsed-ms :correlation :time})

(def ^:private error-tree-keys
  #{:event :tags})

(defn- public-error?
  [opts]
  (= :rf.egress/public-error (:rf.egress/profile opts)))

(defn- project-error-record
  [record opts elision-opts]
  (let [base (select-keys record error-summary-keys)
        ;; Tree-shaped slots → walker. The `:event` slot is REGISTRATION-owned
        ;; (EP-0015): apply the event handler's marks before the
        ;; frame-policy walk. `:tags` is frame-policy-only (it is the generic
        ;; non-event tree-lift from `route-error-record!`).
        with-tree (reduce
                    (fn [acc k]
                      (if (contains? record k)
                        (assoc acc k (if (= k :event)
                                       (project-event-slot (get record k) elision-opts)
                                       (walk-slot (get record k) elision-opts)))
                        acc))
                    base
                    error-tree-keys)]
    ;; `:exception` is author-built; a public-error projection NEVER carries
    ;; internal raw values, so it is dropped. Other profiles walk it (an
    ;; opaque non-tree exception object passes through the walker unchanged).
    (cond
      (public-error? opts)
      with-tree

      (contains? record :exception)
      (assoc with-tree :exception (walk-slot (:exception record) elision-opts))

      :else
      with-tree)))

;; ---- derived-tree record (`:rf.observe/derived-tree`) --------------------
;;
;; A DERIVED tree re-surfaces a frame's app-db-sensitive value at a NON-app-db
;; position the PATH-based walker cannot reach — a token copied out of
;; `[:auth :token]` into rendered hiccup at `[1 :value]`, a resolved
;; `:effective-args` map, a snapshot body, a rendered DOM node.
;;
;; EP-0025 §"What is removed": the value-match (taint-by-equality) redaction of
;; such re-keyed copies is REMOVED — it is propagation/taint by another name,
;; which a HYGIENE helper does not earn. So `:rf.observe/derived-tree` is now a
;; PATH-BASED projection: each tree slot is walked through `elide-wire-value`
;; against the frame's classification registry (frame- / EP-0025-effect- /
;; flow-sourced declarations, unioned). A value re-keyed off its app-db path is
;; NOT covered and ships RAW — INTENDED FAIL-OPEN. The off-box tool consumers
;; (Story-MCP, re-frame2-pair) keep PROJECTING the record (it stays the one
;; boundary they name), but the projection no longer guarantees re-keyed values
;; are redacted; a consumer that needs that must classify the app-db PATH.
;;
;; The record carries:
;;   :tree       the derived value (the SINGLE-TREE form), OR a map whose
;;               named `:slot-keys` are walked (the MULTI-SLOT form);
;;   :slot-keys  nil/empty ⇒ `:tree` IS the derived tree; a seq ⇒ `:tree` is a
;;               map and each present key's value is walked.
;;
;; Profile-aware: under `:rf.egress/local-raw` (or an explicit
;; `:rf.size/include-sensitive? true`) the tree passes through verbatim — the
;; trusted-local operator's deliberate raw read.

(defn- project-derived-tree
  "Project a `:rf.observe/derived-tree` record (EP-0025 B4, rf2-ojp8pi). The
  ONE public boundary a derived tree (rendered hiccup / DOM, `:effective-args`,
  a snapshot body, a plan-resolved value slot) projects through before off-box
  egress. `frame-id` is the already-seeded owning frame.

  EP-0025: the value-match (taint-by-equality) engine is REMOVED. A derived tree
  re-surfaces a frame's app-db-sensitive value at a NON-app-db position the
  PATH-based walker cannot reach (a token copied out of `[:auth :token]` into
  rendered hiccup at `[1 :value]`). EP-0025 §\"What is removed\" disclaims
  value-match as propagation/taint by another name. So this projection is now
  PATH-BASED: each tree slot is walked through `elide-wire-value` against the
  frame's classification. For a re-keyed value this is a NO-OP — the value ships
  RAW (FAIL-OPEN). This is INTENDED: hygiene, not a guarantee. A consumer that
  needs a value redacted in a derived tree must classify its app-db PATH; a
  re-keyed copy is not covered.

  `:slot-keys` nil/empty ⇒ `:tree` IS the derived tree and is walked whole; a
  seq ⇒ `:tree` is a map and each present key's value is walked.

  Profile-aware opt-out: under `:rf.egress/local-raw` / explicit
  `:rf.size/include-sensitive? true` the tree passes through verbatim."
  [record frame-id elision-opts]
  (let [tree        (:tree record)
        ;; A derived tree's positions are NON-app-db, so the path walk is
        ;; FAIL-OPEN by nature (a re-keyed value ships raw). It must therefore
        ;; never fail CLOSED: `elide-wire-value` redacts a whole value to the
        ;; `:rf/redacted` sentinel when the frame is unresolvable / not live —
        ;; correct for an app-db slot, WRONG for a derived tree (it would turn
        ;; the whole tree into a sentinel keyword). So the derived-tree walk
        ;; runs ONLY against a LIVE frame; otherwise the tree ships as-is.
        live-frame? (and (some? frame-id) (some? (frame/frame frame-id)))]
    (if (or (true? (:rf.size/include-sensitive? elision-opts))
            (not live-frame?))
      ;; Trusted-local opt-out, OR no live frame to apply path classification
      ;; against ⇒ the tree passes through (fail-open hygiene).
      tree
      (let [wire-opts (assoc (dissoc elision-opts :path) :frame frame-id)
            walk      (fn [v] (elision/elide-wire-value v wire-opts))
            slot-keys (:slot-keys record)]
        (if (empty? slot-keys)
          (walk tree)
          (reduce (fn [m k]
                    (if (and (associative? m) (contains? m k))
                      (update m k walk)
                      m))
                  tree
                  slot-keys))))))

;; ---- dispatch ------------------------------------------------------------

(defn- project-record-by-kind
  "Dispatch a `:rf.observe/*` record to its private per-kind projector.
  An unknown / kindless record is treated as a tree-shaped VALUE and walked
  whole — the direct-read path (`app-db-value` / `sub-cache` / a bare
  value at a `:path` offset, Spec 015 §Direct reads)."
  [record opts elision-opts]
  (case (and (map? record) (:kind record))
    :rf.observe/handled-event
    (project-handled-event record elision-opts)

    :rf.observe/error
    (project-error-record record opts elision-opts)

    :rf.observe/derived-tree
    (project-derived-tree record (:frame opts) elision-opts)

    ;; No `:kind` (or unknown kind) ⇒ treat the whole input as a
    ;; tree-shaped value and walk it. This is the direct-read / bare-value
    ;; egress path the §13 examples use:
    ;;   (rf/project-egress value {:frame :app/main :path [:auth]
    ;;                             :rf.egress/profile :rf.egress/off-box-tool})
    (walk-slot record elision-opts)))

(defn project-egress
  "Project `record-or-value` for egress across a trust boundary, returning
  a value safe to ship under the resolved profile.

  THE public, record-level boundary primitive (EP-0015 §10/§11). The
  required step before any off-box sink. It dispatches on a record's
  `:kind` (the `:rf.observe/*` record kinds — handled-event, error,
  derived-tree) to a PRIVATE per-kind projector, and falls back to walking a
  kindless input as a tree-shaped VALUE (the direct-read path). For every
  tree-shaped slot it DELEGATES to `elide-wire-value` against the frame's
  classification — it does NOT reimplement the walker (EP-0015 §11).

  A `:rf.observe/derived-tree` record (EP-0025 B4, rf2-ojp8pi) carries `:tree`
  (the derived value, or a map when `:slot-keys` names which map slots to walk)
  and is PATH-walked through `elide-wire-value` against the frame's
  classification registry (frame- / EP-0025-commit-plane-effect- / flow-sourced
  declarations, unioned at lookup). EP-0025 §\"What is removed\" removed the
  VALUE-match (taint) redaction of values re-keyed off their app-db path: such a
  re-keyed copy is NOT covered and ships RAW (intended FAIL-OPEN — hygiene, not
  a guarantee; classify the app-db PATH to cover a re-keyed value). Under
  `:rf.egress/local-raw` (or explicit `:rf.size/include-sensitive? true`) the
  tree passes through verbatim.

  `opts` is a map:

      {:rf.egress/profile <one of `profiles`>   ;; the named boundary
       :frame             <frame-id>            ;; whose classification applies (override; else the record's own :frame)
       :path              [...]                 ;; offset for a bare-value walk
       :rf.size/include-sensitive? <bool>       ;; explicit override (wins)
       :rf.size/include-large?     <bool>
       :rf.size/include-digests?   <bool>
       :rf.size/threshold-bytes    <int>        ;; pass-through tuning
       :as-of-epoch                <epoch-id>}  ;; pass-through

  Composition (EP-0015 §10): the profile's `:rf.size/*` opt-set is the
  floor; any `:rf.size/*` boolean the caller passes explicitly overlays it
  (the override wins). An unknown `:rf.egress/profile` throws
  `:rf.error/unknown-egress-profile` — the enum is closed.

  Frame resolution (EP-0015): an `:rf.observe/*` record is
  FRAME-BEARING — it carries its OWNING frame under a top-level `:frame`
  slot (the §`project-egress` example record carries `:frame :app/main`
  with opts supplying only `:rf.egress/profile`). That owning frame is the
  frame whose classification governs the record's tree-shaped slots, so
  when `opts` does NOT supply `:frame` it is SEEDED from the record's
  top-level `:frame`. An explicit `:frame` opt still WINS (override
  semantics — it is the caller's deliberate reclassification), and a
  kindless / frameless input seeds nothing. This seed is what lets the
  record-owned-frame call shape walk every tree slot under the record's
  OWN frame: without it those slots would fall to the AMBIENT (or no)
  frame, over-redacting off-box slots and, under a sensitive opt-in
  profile, shipping raw values the record's frame would have governed.

  Fail-closed (EP-0002 / Spec 015 §Direct reads): a tree-shaped slot is
  projected only when the frame is KNOWN — the explicit `:frame` opt, the
  record's owning `:frame`, or the carried-invariant scope. With no frame
  from ANY of those and no `:rf.size/include-sensitive? true` opt-out the
  delegated walker redacts the whole value to `:rf/redacted` rather than
  borrow another frame's policy. `project-egress` does NOT synthesise
  `:rf/default`.

  Per [Spec 015 §`project-egress`](../../../../../spec/015-Data-Classification.md#project-egress--the-record-level-boundary-primitive)."
  ([record-or-value] (project-egress record-or-value nil))
  ([record-or-value opts]
   ;; The record is frame-bearing: seed `:frame` from its owning `:frame`
   ;; slot when opts omits one (an explicit `:frame` opt WINS). A kindless
   ;; / frameless input adds nothing, so the fail-closed posture below is
   ;; preserved when no frame is known from any source.
   (let [opts         (let [record-frame (and (map? record-or-value)
                                              (:frame record-or-value))]
                        (if (and record-frame (not (:frame opts)))
                          (assoc opts :frame record-frame)
                          opts))
         elision-opts (resolve-elision-opts opts)]
     (project-record-by-kind record-or-value opts elision-opts))))
