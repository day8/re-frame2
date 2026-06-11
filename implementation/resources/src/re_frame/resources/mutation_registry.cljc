(ns re-frame.resources.mutation-registry
  "Mutation registration — `reg-mutation` / `clear-mutation` and the
  registry-side introspection accessors. Per Spec 016 §Deferred slices
  (mutations, first public-beta gate) and EP-0003 §Mutations.

  A mutation is a registrar entry under the `:mutation` kind (the
  causal-write counterpart of the `:resource` kind). `reg-mutation`
  validates the spec — the REQUIRED `:request` (the Spec 014 managed-HTTP
  args map the write lowers into) and `:params-schema` — and writes the
  entry; `clear-mutation` is a registration-lifecycle removal that ALSO
  disposes the mutation's runtime instances (EP-0003 §Public API:
  \"clear the mutation registration and its runtime instances, not
  masquerade as a form-level error reset\").

  Mirrors `re-frame.resources.registry` (the resource registrar) so the two
  registrars read as one family; the params-validation + (optional) scope
  resolution reuse the resource registry's pluggable late-bound Malli
  validator and canonicalization."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.resources.state :as state]
            [re-frame.source-coords :as source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :mutation registrar kind ----------------------------------------

(def mutation-kind
  "The registrar kind for mutations (`:mutation`). Per Spec 016 §Deferred
  slices / EP-0003 §Mutations. Added to the core registrar's closed kind
  set (a Spec change); mutations register their spec under this kind."
  :mutation)

;; ---- spec validation (fail-closed at the authoring boundary) -------------

(defn- registration-error
  "Build the canonical thrown-error shape (Spec 009 §The thrown-error
  shape) for a `reg-mutation` validation failure."
  [error-id where reason extra]
  (ex-info (str error-id)
           (merge {:rf.error/id error-id
                   :where       where
                   :recovery    :fix-registration
                   :reason      reason}
                  extra)))

(defn- validate-mutation-spec!
  "Fail loudly at the authoring boundary when a mutation spec omits the
  REQUIRED keys (EP-0003 §Mutations). `:request` is REQUIRED (the Spec 014
  managed-HTTP args map the write lowers into, the only initial-scope
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
             :rf.error/invalid-mutation-spec
             'rf/reg-mutation
             (str "mutation " mutation-id "'s spec must be a map, got "
                  (pr-str (type spec)))
             {:mutation-id mutation-id :value spec})))
  (when-not (contains? spec :request)
    (throw (registration-error
             :rf.error/invalid-mutation-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares no :request. For "
                  ":transport :rf.http/managed (the only initial-scope "
                  "transport) :request returns a Spec 014 managed-HTTP args "
                  "map (the causal write). Per EP-0003 §Mutations.")
             {:mutation-id mutation-id})))
  (when-not (contains? spec :params-schema)
    (throw (registration-error
             :rf.error/invalid-mutation-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares no :params-schema. "
                  ":params-schema is REQUIRED — it validates and canonicalizes "
                  "the write's params (which the :request / :invalidates / "
                  ":patches fns close over). Per EP-0003 §Mutations.")
             {:mutation-id mutation-id})))
  nil)

;; ---- reg-mutation / clear-mutation ---------------------------------------

(defn reg-mutation
  "Register a mutation under `mutation-id` with `mutation-spec`. Per Spec
  016 §Deferred slices / EP-0003 §Mutations.

  The spec carries the REQUIRED `:request` (a Spec 014 managed-HTTP args
  map — the causal write) and `:params-schema`, plus optional:

  - **`:invalidates`** — `(fn [params result] -> #{tag …})` — the resource
    tags this mutation's success (and optionally failure) invalidates;
  - **`:patches`** — `(fn [params result] -> {scoped-key-or-spec patch-fn})`
    — controlled resource-entry patches applied on success;
  - **`:populates`** — `(fn [params result] -> {resource-spec value})` —
    controlled resource-entry seeds applied on success;
  - **`:scope`** — the cache scope the invalidation / patch defaults to
    (same scope rules as resources; OPTIONAL — defaults from the execute
    payload, else `:rf.scope/global`);
  - **`:invalidate-timing`** — `:after-success` (default) | `:before-request`
    | `:after-failure` | `:after-settle`;
  - **`:retry`** — write retries are OPT-IN (EP-0003 §Mutations) and ride
    the Spec 014 managed-HTTP args' own `:retry`; nothing here forces them;
  - **`:transport`** — initial scope: `:rf.http/managed` (the only built-in);
  - **`:doc`**.

  Validates the spec (the REQUIRED `:request` + `:params-schema`) and
  writes a `:mutation`-kind registrar entry carrying the spec plus captured
  source coords. Returns `mutation-id` per the `reg-*` return-value
  convention."
  [mutation-id mutation-spec]
  (validate-mutation-spec! mutation-id mutation-spec)
  (registrar/register!
    mutation-kind
    mutation-id
    (source-coords/merge-coords
      (merge {:doc (:doc mutation-spec)}
             {:rf/mutation mutation-spec
              :handler-fn  (:request mutation-spec)})))
  mutation-id)

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
  (registrar/unregister! mutation-kind mutation-id)
  mutation-id)

;; ---- registry-side introspection -----------------------------------------

(defn mutation-meta
  "Return the registered mutation's spec map (`:request`, `:params-schema`,
  `:invalidates`, `:patches`, `:populates`, `:scope`, `:invalidate-timing`,
  `:transport`, `:doc`, source coords) for `mutation-id`, or nil if no
  mutation is registered under that id. The introspection counterpart of
  `resource-meta`. Per EP-0003 §Mutations / Xray."
  [mutation-id]
  (:rf/mutation (registrar/lookup mutation-kind mutation-id)))

(defn mutation-ids
  "Return a vector of every registered mutation id. The registry-side half
  of the mutation introspection (the runtime-side per-frame instance table
  lives in the subs / accessors). Per EP-0003 §Mutations / Xray."
  []
  (vec (registrar/ids mutation-kind)))

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
                    "Per EP-0003 §Mutations.")
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
  EDN discipline applies."
  [mutation-id spec params where]
  (let [params (or params {})
        schema (:params-schema spec)]
    (state/reject-non-edn! params where :params mutation-id)
    (when schema
      (let [validate (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
        (when (and validate (not (validate schema params)))
          (let [explain (late-bind/get-fn-cached :schemas/explain-with-registered-fn)]
            (throw (registration-error
                     :rf.error/mutation-invalid-params
                     where
                     (str "mutation " mutation-id " params do not conform to "
                          ":params-schema. Per EP-0003 §Mutations.")
                     {:mutation-id mutation-id
                      :params      params
                      :error       (when explain (explain schema params))}))))))
    (state/canonicalize params)))

(defn resolve-scope
  "Resolve the cache scope a mutation's invalidation / patch defaults to,
  in precedence order: the execute payload `:scope`, else the mutation
  spec's `:scope`, else `:rf.scope/global`. A mutation has NO fail-closed
  scope requirement (it is a causal write, not a cached read with a leak
  boundary) — the invalidation it triggers is scoped, so the scope just
  decides which cache scope the success-time `:invalidate-tags` /
  patch / populate target.

  Routes the resolved concrete scope through the SAME shared validation
  path resources use (`state/canonicalize-scope`, rf2-lzv9xc): a host /
  opaque scope value is rejected, a misspelled reserved `:rf.scope/*`
  keyword is rejected fail-closed (rf2-pd7akw), and the historical
  `[:rf.scope/global]` singleton-vector spelling normalizes to bare
  `:rf.scope/global` (rf2-vv87xz) — so a mutation can never invalidate /
  patch a silent WRONG (or second, distinct global) cache scope. Returns
  the canonical scope. Per EP-0003 §Mutations (scoped execution, same
  cache-scope rules as resources)."
  [mutation-id spec payload-scope]
  (let [scope (or payload-scope (:scope spec) :rf.scope/global)]
    (state/canonicalize-scope scope 'rf.mutation/execute mutation-id)))
