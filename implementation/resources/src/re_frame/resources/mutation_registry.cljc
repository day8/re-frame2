(ns re-frame.resources.mutation-registry
  "Mutation registration — `reg-mutation` / `clear-mutation` and the
  registry-side introspection accessors. Per Spec 016 §Mutations.

  A mutation is a registrar entry under the `:mutation` kind (the
  causal-write counterpart of the `:resource` kind). `reg-mutation`
  validates the spec — the REQUIRED `:request` (the Spec 014 managed-HTTP
  args map the write lowers into) and `:params-schema` — and writes the
  entry; `clear-mutation` is a registration-lifecycle removal of the
  registrar entry ONLY. Runtime-instance disposal (drop the mutation's
  instances in each affected frame + best-effort abort their in-flight
  work) is the CAUSAL `:rf.mutation/clear` event's job (it carries a frame
  target), distinct from this process-level registration removal — see the
  `clear-mutation` docstring.

  Mirrors `re-frame.resources.registry` so the two registrars read as one
  family. Both delegate params validation and canonicalization to
  `re-frame.resources.params`; mutation scope normalization uses the shared
  concrete-scope boundary in `re-frame.resources.state`."
  (:require [re-frame.error :as rf.error]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.params :as rf.resources.params]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :mutation registrar kind ----------------------------------------

(def mutation-kind
  "The registrar kind for mutations (`:mutation`). Per Spec 016 §Deferred
  slices / EP-0003 §Mutations. Added to the core registrar's closed kind
  set (a Spec change); mutations register their spec under this kind."
  :mutation)

;; ---- spec validation (fail-closed at the authoring boundary) -------------

(def invalidate-timings
  "The CLOSED enum of valid `:invalidate-timing` values (Spec 016
  §Mutations). `nil` is also valid at registration — it defaults to
  `:after-success` at runtime. Any non-nil value outside this set is a typo
  (e.g. `:after-succes`) that would silently skip every invalidation timing
  branch — rejected fail-closed by `validate-mutation-spec!`."
  #{:before-request :after-success :after-failure :after-settle})

(defn- registration-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) for a `reg-mutation` validation failure."
  [error-id where reason extra]
  (rf.error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

(defn- validate-mutation-spec!
  "Fail loudly at the authoring boundary when a mutation spec omits the
  REQUIRED keys (EP-0003 §Mutations). `:request` is REQUIRED (the Spec 014
  managed-HTTP args map the write lowers into, the only built-in
  transport); `:params-schema` is REQUIRED (validates + canonicalizes the
  write's params). `:scope` is OPTIONAL for a mutation (unlike a resource):
  a mutation is a causal write, not a cached read, so it has no fail-closed
  cache-leak boundary of its own — its invalidation / patch SCOPE defaults
  from the payload (or `:rf.scope/global`) and the `:invalidates` /
  `:patches` / `:populates` fns are themselves optional. Fails in dev AND
  prod (a caller bug)."
  [mutation-id spec]
  (when-not (map? spec)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id "'s spec must be a map, got "
                  (pr-str (type spec)))
             {:mutation-id mutation-id :value spec})))
  (when-not (contains? spec :request)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares no :request. For "
                  ":transport :rf.http/managed (the only built-in "
                  "transport) :request returns a Spec 014 managed-HTTP args "
                  "map (the causal write). Per Spec 016 §Mutations.")
             {:mutation-id mutation-id})))
  (when-not (contains? spec :params-schema)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares no :params-schema. "
                  ":params-schema is REQUIRED — it validates and canonicalizes "
                  "the write's params (which the :request / :invalidates / "
                  ":patches fns close over). Per Spec 016 §Mutations.")
             {:mutation-id mutation-id})))
  ;; :invalidate-timing is a CLOSED four-value enum (Spec 016 §Mutations). It
  ;; is OPTIONAL (nil defaults to :after-success at runtime), but a non-nil
  ;; value outside the enum is a typo (e.g. :after-succes) that would register
  ;; cleanly and then SILENTLY skip every invalidation timing branch at
  ;; runtime (`(or (:invalidate-timing spec) :after-success)` defaults nil,
  ;; but a typo is neither nil nor matched by the `#{…}` timing guards). Reject
  ;; it loudly here so the failure is at the authoring boundary, not a silent
  ;; runtime no-op (rf2-t8j7oj). Fails in dev AND prod (a caller bug).
  (let [timing (:invalidate-timing spec)]
    (when (and (some? timing) (not (contains? invalidate-timings timing)))
      (throw (registration-error
               :rf.error/mutation-bad-spec
               'rf/reg-mutation
               (str "mutation " mutation-id " declares an :invalidate-timing of "
                    (pr-str timing) " outside the closed enum "
                    (pr-str (sort invalidate-timings)) ". A typo (e.g. "
                    ":after-succes) would register cleanly and then silently "
                    "skip every invalidation timing branch at runtime. Per "
                    "Spec 016 §Mutations.")
               {:mutation-id       mutation-id
                :invalidate-timing timing
                :valid             invalidate-timings})))
    ;; EP-0019 Rider 3 — `:optimistic` / `:optimistic-tags` are INCOMPATIBLE
    ;; with `:invalidate-timing :before-request`. A `:before-request` timing
    ;; STALES the touched entries before the request; composing that with an
    ;; optimistic apply that immediately RE-POPULATES the same entries is
    ;; contradictory (stale-then-optimistic-fresh). Reject it LOUDLY at the
    ;; authoring boundary (a registration error, NOT a silent precedence rule);
    ;; optimistic writes use the default `:after-success` timing. Per Spec 016
    ;; §Optimistic mutations / EP-0019 Rider 3.
    (when (and (= :before-request timing)
               (or (contains? spec :optimistic) (contains? spec :optimistic-tags)))
      (throw (registration-error
               :rf.error/mutation-optimistic-before-request
               'rf/reg-mutation
               (str "mutation " mutation-id " declares an optimistic plan "
                    "(:optimistic / :optimistic-tags) together with "
                    ":invalidate-timing :before-request — these are "
                    "INCOMPATIBLE. A :before-request invalidation STALES the "
                    "touched entries before the request, and an optimistic "
                    "apply immediately RE-POPULATES the same entries: the two "
                    "are contradictory (stale-then-optimistic-fresh). Optimistic "
                    "writes use the default :after-success timing — drop the "
                    ":invalidate-timing :before-request (or drop the optimistic "
                    "plan). Per Spec 016 §Optimistic mutations / EP-0019 Rider 3.")
               {:mutation-id       mutation-id
                :invalidate-timing timing
                :has-optimistic?   (contains? spec :optimistic)
                :has-optimistic-tags? (contains? spec :optimistic-tags)}))))
  ;; EP-0019 Decision 3 — `:on-conflict` is a CLOSED two-value enum
  ;; (`:invalidate` default | `:force`). It is OPTIONAL (nil defaults to
  ;; `:invalidate` at settle time), but a non-nil value outside the enum is a
  ;; typo (e.g. `:invlaidate`, or a mistyped `:force`) that would register
  ;; cleanly and then SILENTLY fall back to the default `:invalidate` rollback
  ;; rule at settle time — masking the author's intent (a mistyped `:force`
  ;; would then NOT clobber a concurrent write as the author expected). Reject
  ;; it loudly at the authoring boundary. Per Spec 016 §Optimistic settle.
  (let [oc (:on-conflict spec)]
    (when (and (some? oc) (not (contains? rf.resources.mutation-runtime/on-conflict-policies oc)))
      (throw (registration-error
               :rf.error/mutation-bad-spec
               'rf/reg-mutation
               (str "mutation " mutation-id " declares an :on-conflict of "
                    (pr-str oc) " outside the closed enum "
                    (pr-str (sort rf.resources.mutation-runtime/on-conflict-policies)) ". A typo "
                    "(e.g. :invlaidate, or a mistyped :force) would register "
                    "cleanly and then silently fall back to the default "
                    ":invalidate rollback rule at settle time, masking the "
                    "author's intent. Per Spec 016 §Optimistic settle / "
                    "EP-0019 Decision 3.")
               {:mutation-id mutation-id
                :on-conflict oc
                :valid       rf.resources.mutation-runtime/on-conflict-policies}))))
  ;; EP-0025 §subsystems (rf2-h3d8tf): reject a malformed projection-relative
  ;; `:sensitive` / `:large` data-classification declaration fail-loud at the
  ;; registration boundary — the same shape contract + posture as the resource
  ;; + machine declarations (a mutation's work-row `:params` carry the same
  ;; privacy class as a resource's, Spec 016 clause 4).
  (when-let [{:keys [axis reason]} (rf.resources.classification/classification-declaration-defect spec)]
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares a malformed " axis
                  " data-classification: " reason ". Per EP-0025 a mutation "
                  "declares :sensitive / :large as a vector of projection-relative "
                  "`:rf/path` vectors (e.g. {:sensitive [[:params :token]]}).")
             {:mutation-id mutation-id :axis axis :value (get spec axis)})))
  nil)

;; ---- reg-mutation / clear-mutation ---------------------------------------

(defn reg-mutation
  "Register a mutation under `mutation-id`. Per the canonical Spec 001 3-slot
  grammar (rf2-wvh95f F1):

      (rf/reg-mutation :article/save
        {:doc \"Persist an article.\"
         :params-schema [:map [:slug :string] [:body :string]]
         :invalidates   (fn [params _result] #{:articles})}
        (fn [{:keys [slug body]} _ctx]
          {:request {:method :put :url (str \"/api/articles/\" slug)
                     :body {:body body}}}))

  The `:request` fn — the mutation's HANDLER (the Spec 014 managed-HTTP causal
  write) — is the THIRD slot; the middle slot is the reflection + config
  metadata map. Splitting the handler out of the fused spec restores clean
  doc-DCE (the middle slot is now a pure metadata map). A `:request` left
  INSIDE the metadata map is rejected loudly as a mislocated key.

  The metadata map carries the REQUIRED `:params-schema`, plus optional:

  - **`:invalidates`** — `(fn [params result] -> #{tag …})` — the resource
    tags this mutation's success (and optionally failure) invalidates;
  - **`:patches`** — `(fn [params result] -> {target patch-fn})` — controlled
    resource-entry patches applied on success; each KEY is a map-form exact
    target `{:resource :params :scope}` (EP-0016 Rider 2 — the only public
    input form);
  - **`:populates`** — `(fn [params result] -> {target value})` — controlled
    resource-entry seeds applied on success (an AUTHORITATIVE load, Rider 1);
    each KEY is the same map-form exact target;
  - **`:removes`** — `(fn [params result] -> [target …])` — controlled
    resource-entry REMOVALS applied on success (a delete write that drops the
    cached entry — EP-0016 Rider 2; accepted replies apply patches, populates,
    invalidates, AND removes); each element is the same map-form exact target
    `{:resource :params :scope}`. A removed key's in-flight attempt is
    best-effort aborted + its work row settled `:cancelled` (as
    `:rf.resource/remove` does), and the key flows into the reply
    `:affected-keys`;
  - **`:optimistic`** — `(fn [params] -> {target patch-fn})` — the FORWARD
    optimistic plan applied to the resource cache BEFORE the request settles
    (phase 1.5, EP-0019 Decision 1). NOTE the signature has NO `result` (the
    reply does not exist yet). Each KEY is the same map-form exact target
    `{:resource :params :scope}`; each `patch-fn` is `(fn [old-data] -> new-data)`
    (or `nil` for an optimistic REMOVE, EP-0019 Open Issue 6). The runtime
    SNAPSHOTS each touched entry (the truthful inverse) + its `:revision` before
    applying — the author writes the forward patch only. Each target's scope is
    FAIL-CLOSED (a `{:from-db …}` resolving nil drops the target — Rider 2);
  - **`:optimistic-tags`** — `(fn [params] -> [{:scope … :tags #{…} :patch fn}])`
    — the TAG-ADDRESSED optimistic twin (EP-0019 Decision 4): each descriptor
    optimistically patches EVERY cached entry carrying the descriptor's tags in
    its resolved scope (the cross-view-consistency demand). Reuses the same tag
    index + per-target scope descriptors as `:invalidates`; each matched entry
    gets the same snapshot-inverse + revision treatment;
  - **`:on-conflict`** — `:invalidate` (default) | `:force` — the EP-0019
    Decision 3 rollback conflict rule (consumed by the SETTLE slice; recorded
    here on the spec). `:invalidate` refetches the authoritative value on a
    contested rollback; `:force` restores the recorded inverse anyway;
  - **`:scope`** — the cache scope the invalidation / patch defaults to
    (same scope rules as resources; OPTIONAL — defaults from the execute
    payload, else `:rf.scope/global`);
  - **`:invalidate-timing`** — `:after-success` (default) | `:before-request`
    | `:after-failure` | `:after-settle`;
  - **`:retry`** — write retries are OPT-IN (EP-0003 §Mutations) and ride
    the Spec 014 managed-HTTP args' own `:retry`; nothing here forces them;
  - **`:transport`** — `:rf.http/managed` (the only built-in);
  - **`:doc`**.

  Validates the reconstructed spec (the REQUIRED `:request` + `:params-schema`)
  and writes a `:mutation`-kind registrar entry carrying the spec plus captured
  source coords. Returns `mutation-id` per the `reg-*` return-value
  convention."
  [mutation-id metadata request-fn]
  ;; The metadata MIDDLE slot must be a map BEFORE any
  ;; `assoc`/`contains?` runs against it. Reconstructing `:request` onto a
  ;; non-map metadata would
  ;; otherwise leak a raw host `IllegalArgumentException` ("Key must be
  ;; integer") instead of the public `:rf.error/mutation-bad-spec`. Mirrors
  ;; reg-route's `route-bad-metadata` non-map guard. The catalogue row
  ;; already documents "or the spec was not a map" with a `:value` slot.
  (when-not (map? metadata)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id "'s metadata (the MIDDLE slot) must "
                  "be a map, got " (pr-str (type metadata)) ". The grammar is "
                  "(reg-mutation " mutation-id " {…} "
                  "request-fn): the config metadata map is the SECOND slot, "
                  "the :request write fn is the THIRD.")
             {:mutation-id mutation-id :value metadata})))
  ;; `:request` is the third-slot VALUE (the handler). A `:request`
  ;; left INSIDE the metadata map is a mislocated key; reject it loudly.
  (when (contains? metadata :request)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares :request inside its "
                  "metadata map — the request handler is the "
                  "THIRD slot: (reg-mutation " mutation-id " {…} request-fn). "
                  "Move the write fn out of the metadata map into the value "
                  "slot.")
             {:mutation-id mutation-id :value (:request metadata)})))
  (let [mutation-spec (assoc metadata :request request-fn)]
    (validate-mutation-spec! mutation-id mutation-spec)
    (rf.registrar/register!
      mutation-kind
      mutation-id
      (rf.source-coords/merge-coords
        (merge {:doc (:doc mutation-spec)}
               {:rf/mutation mutation-spec
                :handler-fn  (:request mutation-spec)})))
    mutation-id))

(defn clear-mutation
  "Remove a registered mutation (registration-lifecycle, NOT a form-error
  reset). Per Spec 016 §Deferred slices / EP-0003 §Public API.

  Clears the registrar entry. The runtime-instance disposal (drop the
  mutation's `:rf.runtime/mutations` instances in each affected frame +
  best-effort abort their in-flight work) is the CAUSAL `:rf.mutation/clear`
  event's job (it has a frame target); `clear-mutation` is the
  process-level registration removal. EP-0003 §Public API: \"clear the
  mutation registration and its runtime instances, not masquerade as a
  form-level error reset.\"

  No-op (returns `mutation-id`) when the id is not registered."
  [mutation-id]
  (rf.registrar/unregister! mutation-kind mutation-id)
  mutation-id)

;; ---- registry-side introspection -----------------------------------------

(defn mutation-meta
  "Return the registered mutation's spec map (`:request`, `:params-schema`,
  `:invalidates`, `:patches`, `:populates`, `:removes`, `:optimistic`,
  `:optimistic-tags`, `:on-conflict`, `:scope`, `:invalidate-timing`,
  `:transport`, `:doc`, source coords) for `mutation-id`, or nil if no
  mutation is registered under that id. The introspection counterpart of
  `resource-meta`. Per EP-0003 §Mutations / Xray."
  [mutation-id]
  (:rf/mutation (rf.registrar/lookup mutation-kind mutation-id)))

(defn mutation-ids
  "Return a vector of every registered mutation id. The registry-side half
  of the mutation introspection (the runtime-side per-frame instance table
  lives in the subs / accessors). Per EP-0003 §Mutations / Xray."
  []
  (vec (rf.registrar/ids mutation-kind)))

(defn require-mutation-spec!
  "Look the mutation spec up by `mutation-id`, throwing
  `:rf.error/mutation-not-registered` (the loud, fail-closed boundary) when
  no `:mutation`-kind registrar entry exists. `where` names the call-site
  public surface. Returns the spec map. Per EP-0003 §Mutations."
  [mutation-id where]
  (or (mutation-meta mutation-id)
      (throw (registration-error
               :rf.error/mutation-not-registered
               where
               (str "no mutation is registered under " mutation-id
                    " — call rf/reg-mutation before :rf.mutation/execute. "
                    "Per Spec 016 §Mutations.")
               {:mutation-id mutation-id}))))

;; ---- params validation + canonicalization --------------------------------

(defn validate+canonicalize-params
  "Validate `params` against the mutation's REQUIRED `:params-schema`
  (the same pluggable late-bound Malli validator resources use — no static
  schemas dep), reject non-EDN / host values loudly, then return the
  canonical params. Throws `:rf.error/mutation-invalid-params` on a
  schema-conformance failure. Delegates the heavy lifting to the resource
  registry's `state/reject-non-edn!` + canonicalization so the two
  registrars validate identically. Per EP-0003 §Mutations.

  NOTE: a mutation's params need not be EDN-cacheable the way a resource's
  cache key must be (a mutation is not cached by params) — but the
  `:invalidates` / `:patches` fns close over the canonical params and the
  instance row stores them (serializable, for Xray / SSR), so the same
  EDN discipline applies.

  `nil` vs missing is schema-defined (rf2-hgy5kf): a caller threads
  `state/missing-params` for an ABSENT `:params` slot (lowered to the
  documented `{}` default via `state/default-omitted-params`), while a PRESENT
  explicit `nil` passes THROUGH to `:params-schema` validation +
  canonicalization — the schema, not a blanket `(or params {})`, decides
  whether nil conforms. The two registrars apply the SAME presence-aware
  policy so a validation boundary never performs an accidental API default
  (EP-0012 §canonical-forms).

  A THIN wrapper over the shared `re-frame.resources.params/validate+
  canonicalize` pipeline (rf2-7rbb7t) — the SAME leaf the resource registrar
  wraps: this arm supplies ONLY the mutation-family error descriptor
  (`:rf.error/mutation-invalid-params` with a `:mutation-id`); the
  omitted-default, non-EDN rejection, late-bound schema validation,
  invalid-param REDACTION (the shared classification seam), and canonicalization
  are owned once there, so the two registrars can never drift again."
  [mutation-id spec params where]
  (rf.resources.params/validate+canonicalize
    mutation-id spec params where
    (fn [redacted-params redacted-error]
      (registration-error
        :rf.error/mutation-invalid-params
        where
        (str "mutation " mutation-id " params do not conform to "
             ":params-schema. Per Spec 016 §Mutations.")
        {:mutation-id mutation-id
         :params      redacted-params
         :error       redacted-error}))))

(defn resolve-scope
  "Resolve the cache scope a mutation's invalidation / patch defaults to,
  in precedence order: the execute payload `:scope`, else the mutation
  spec's `:scope`, else `:rf.scope/global`. A mutation's EXECUTION scope has
  NO fail-closed scope requirement on ABSENCE (it is a causal write, not a
  cached read with a leak boundary), so defaulting to `:rf.scope/global`
  when NO scope is named anywhere is correct here. This is distinct from the
  mutation's INVALIDATION scope, which IS fail-closed:
  `:rf.resource/invalidate-tags` throws
  `:rf.error/resource-invalidate-scope-required` without an explicit scope
  (rf2-pvdae1), `:cross-scope? true` being the only scope-agnostic opt-out.
  The resolved scope below is what the success-time invalidation supplies, so
  the two compose: this fail-open-on-absence execution default decides which
  cache scope the success-time `:invalidate-tags` / patch / populate target.

  **The selected scope is a public ScopeInput** (rf2-l11670, Spec 016
  §Resolver references): a CONCRETE scope value OR a `{:from-db
  <resolver-id>}` named-resolver reference, resolved against `db` (the
  execute handler's app-db coeffect) at event-execution time through the
  single symmetric resolution arm (`rf.resources.scope-registry/resolve-scope-input`) —
  `:rf.mutation/execute` is its third consumer, alongside the direct
  invalidate-tags / clear-scope events (rf2-oo8cv7). A reference is NEVER
  canonicalized literally (a literal `{:from-db …}` map keys nothing, so
  every downstream tag match fails as a silent zero-match — the exact
  failure mode rf2-oo8cv7 eliminated on invalidate-tags). The mutation
  engine, trace, instance record, and continuation projections only ever
  see the RESOLVED concrete scope. An unregistered resolver id throws
  `:rf.error/resource-scope-not-registered` (via the shared arm) BEFORE any
  mutation start.

  Nil policy (Spec 016's fail-closed law): an EXPLICITLY supplied reference
  — payload or spec tier — that resolves NIL throws
  `:rf.error/resource-scope-unresolved-reference`. Execute is
  scope-requiring for a supplied reference, like ensure / invalidate-tags —
  NOT clear-scope's warn/no-op, and NOT the fail-open global default: a
  session-derived write executing while logged out must fail loudly, never
  silently operate on global entries (the cross-viewer leak). Fail-open
  governs ONLY the absent case (no payload scope, no spec scope).

  The concrete arm routes through the SAME shared validation path resources
  use (`state/canonicalize-scope` via `resolve-scope-input`, rf2-lzv9xc): a
  host / opaque scope value is rejected, a misspelled reserved `:rf.scope/*`
  keyword is rejected fail-closed (rf2-pd7akw), and the reserved global
  scope wrapped as the singleton `[:rf.scope/global]` is rejected fail-closed
  in favour of the canonical bare `:rf.scope/global` (rf2-bwwk6l) — so a
  mutation can never invalidate / patch a silent WRONG cache scope. Returns
  the canonical concrete scope. Per EP-0003 §Mutations (hybrid scope:
  fail-open on ABSENT execution scope, fail-closed on a wrong or
  nil-resolving supplied one)."
  [mutation-id spec payload-scope db]
  (let [scope    (or payload-scope (:scope spec) :rf.scope/global)
        from-db? (rf.resources.scope-registry/from-db-reference? scope)
        resolved (rf.resources.scope-registry/resolve-scope-input
                   scope db 'rf.mutation/execute mutation-id)]
    (when (and from-db? (nil? resolved))
      (rf.error/throw-error!
        :rf.error/resource-scope-unresolved-reference
        'rf.mutation/execute
        (str "a :rf.mutation/execute {:from-db …} :scope referenced named "
             "scope resolver " (pr-str (:from-db scope)) ", but it resolved "
             "NIL against the current db — FAIL-CLOSED. A derived scope that "
             "cannot resolve is the unresolved condition, never permission "
             "to execute against the global scope or fall through to another "
             "tier. The resolver's declared :inputs are not present in db "
             "(e.g. no logged-in user). Per Spec 016 §Resolver references.")
        {:recovery :fix-scope
         :extra    {:mutation-id mutation-id :from-db (:from-db scope)}}))
    resolved))
