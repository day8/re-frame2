(ns re-frame.resources.mutation-registry
  "Mutation registration (`reg-mutation` / `clear-mutation`) and the
  registry-side accessors, the causal-write counterpart of
  `re-frame.resources.registry`. Both registrars share
  `re-frame.resources.params` for params validation and canonicalization.
  `clear-mutation` removes only the registrar entry; dropping a frame's
  instances is the causal `:rf.mutation/clear` event's job.
  Spec 016 §Mutations (first public-beta gate)."
  (:require [re-frame.error :as rf.error]
            [re-frame.reg-meta :as rf.reg-meta]
            [re-frame.registrar :as rf.registrar]
            [re-frame.resources.classification :as rf.resources.classification]
            [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.resources.params :as rf.resources.params]
            [re-frame.resources.scope-registry :as rf.resources.scope-registry]
            [re-frame.source-coords :as rf.source-coords]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the :mutation registrar kind ----------------------------------------

(def mutation-kind
  "The registrar kind for mutations."
  :mutation)

(def mutation-metadata-keys
  "The bare keys a `reg-mutation` metadata map may carry; any other bare key
  warns `:rf.warning/unknown-registration-key`. `:request` (the third slot)
  and `:retry` (it rides the managed-HTTP args) are absent on purpose.
  Conventions §No silent swallow — recognised input MUST signal."
  #{:doc :params-schema :scope
    :invalidates :patches :populates :removes :invalidate-timing
    :optimistic :optimistic-tags :on-conflict
    :sensitive :large})

;; ---- spec validation (fail-closed at the authoring boundary) -------------

(def invalidate-timings
  "The closed `:invalidate-timing` enum. Nil is also valid and defaults to
  `:after-success` at runtime. Spec 016 §Mutations (first public-beta gate)."
  #{:before-request :after-success :after-failure :after-settle})

(defn- registration-error
  "The canonical thrown-error for a `reg-mutation` validation failure.
  Spec 009 §The thrown-error shape — the `:rf.error/id` ex-data contract."
  [error-id where reason extra]
  (rf.error/thrown-ex-info
    error-id
    where
    reason
    {:recovery :fix-registration
     :extra    extra}))

(defn- validate-mutation-spec!
  "Throws `:rf.error/mutation-bad-spec` (dev and prod) unless the spec is a
  map with a callable `:request` and a `:params-schema`, and its optional
  `:invalidate-timing`, `:on-conflict` and `:sensitive` / `:large` values are
  well formed. `:scope` is optional: a write has no cache-leak boundary of
  its own. Spec 016 §Mutations (first public-beta gate)."
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
             (str "mutation " mutation-id " declares no :request. "
                  ":request returns a Spec 014 managed-HTTP args map (the "
                  "causal write). Per Spec 016 §Mutations.")
             {:mutation-id mutation-id})))
  ;; A non-callable :request would register cleanly and fail at the first
  ;; write (a raw host cast error, or silently a nil args map). Not bare
  ;; `fn?`: the hosts disagree on Vars, and `#'my-write` is the idiomatic
  ;; hot-reload indirection. Not `ifn?`: it admits the silent-nil shapes.
  (when-not (or (fn? (:request spec)) (var? (:request spec)))
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id "'s :request (the THIRD slot) must "
                  "be a fn, got " (pr-str (type (:request spec))) ". The "
                  "grammar is (reg-mutation " mutation-id " {…} "
                  "(fn [params ctx] -> managed-http-args)); a Var (#'my-write) "
                  "is also accepted. A non-callable :request registers cleanly "
                  "and then fails at the first write — as a raw host cast "
                  "error, or (for a keyword/map) silently as a nil request. "
                  "Per Spec 016 §Mutations.")
             {:mutation-id mutation-id :value (:request spec)})))
  (when-not (contains? spec :params-schema)
    (throw (registration-error
             :rf.error/mutation-bad-spec
             'rf/reg-mutation
             (str "mutation " mutation-id " declares no :params-schema. "
                  ":params-schema is REQUIRED — it validates and canonicalizes "
                  "the write's params (which the :request / :invalidates / "
                  ":patches fns close over). Per Spec 016 §Mutations.")
             {:mutation-id mutation-id})))
  ;; A typo'd timing is neither nil nor matched by any timing guard, so it
  ;; would silently skip every invalidation branch at runtime.
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
    ;; :before-request stales the entries an optimistic apply would
    ;; immediately re-populate, so the pair is contradictory.
    ;; Spec 016 §Optimistic writes are fail-closed and scope-bounded.
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
  ;; A typo'd :on-conflict would silently fall back to :invalidate at settle,
  ;; so a mistyped :force would not clobber as the author expected.
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
  "Registers a mutation and returns `mutation-id`:

      (rf/reg-mutation :article/save
        {:doc \"Persist an article.\"
         :params-schema [:map [:slug :string] [:body :string]]
         :invalidates   (fn [params _result] #{:articles})}
        (fn [{:keys [slug body]} _ctx]
          {:request {:method :put :url (str \"/api/articles/\" slug)
                     :body {:body body}}}))

  The middle slot is the metadata map (keys in `mutation-metadata-keys`);
  the third slot is the `:request` fn returning Spec 014 managed-HTTP args.
  Keeping the handler out of the metadata map keeps doc-DCE clean, so a
  `:request` inside it throws `:rf.error/mutation-bad-spec`, as does a
  non-map metadata slot. Spec 016 §Mutations (first public-beta gate)."
  [mutation-id metadata request-fn]
  ;; Before any assoc / contains?, which would otherwise leak a raw host
  ;; exception instead of the public error.
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
    ;; After the spec checks, so a key they refuse throws before anything warns.
    (rf.reg-meta/validate-registration-metadata!
      mutation-kind 'rf/reg-mutation mutation-id metadata mutation-metadata-keys)
    (rf.registrar/register!
      mutation-kind
      mutation-id
      (rf.source-coords/merge-coords
        (merge {:doc (:doc mutation-spec)}
               ;; This registrar map is the kind's own shape, so the caller's
               ;; image-selection stamp would otherwise be dropped before the
               ;; source store sees it. Spec 001 §Production elision contract.
               (select-keys metadata [:ns :rf.provenance/ns])
               {:rf/mutation mutation-spec
                :handler-fn  (:request mutation-spec)})))
    mutation-id))

(defn clear-mutation
  "Removes the registrar entry for `mutation-id` and returns it; a no-op for
  an unregistered id. It does not touch runtime instances, which the causal
  `:rf.mutation/clear` event drops per frame.
  Spec 016 §Mutations (first public-beta gate)."
  [mutation-id]
  (rf.registrar/unregister! mutation-kind mutation-id)
  mutation-id)

;; ---- registry-side introspection -----------------------------------------
;; Artefact-internal, like `resource-meta`. Outside callers use
;;   (:rf/mutation (rf/handler-meta {:kind :mutation :id id}))
;;   (keys (rf/registrations {:kind :mutation}))

(defn mutation-meta
  "The registered spec map for `mutation-id`, or nil. Source coords sit on
  the outer registrar entry, not here."
  [mutation-id]
  (:rf/mutation (rf.registrar/lookup mutation-kind mutation-id)))

(defn require-mutation-spec!
  "The spec map for `mutation-id`; throws `:rf.error/mutation-not-registered`
  when none is registered. `where` names the calling public surface."
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
  "The canonical params for a mutation call, through the shared
  `re-frame.resources.params/validate+canonicalize` pipeline the resource
  registrar also uses, so the two cannot drift. Throws
  `:rf.error/mutation-invalid-params` on a schema failure, carrying the slots
  that pipeline builds from the seam's diagnosis (`:reason :malformed-schema`
  when the schema itself is broken).
  `params` is `state/missing-params` for an absent slot (defaulted to `{}`);
  an explicit nil reaches the schema. A mutation is not cached by params, but
  the instance row stores them, so the same EDN discipline applies.
  Spec 016 §Mutations (first public-beta gate)."
  [mutation-id spec params where]
  (rf.resources.params/validate+canonicalize
    mutation-id spec params where :mutation-params
    (fn [slots]
      (registration-error
        :rf.error/mutation-invalid-params
        where
        (if (= :malformed-schema (:reason slots))
          (str "mutation " mutation-id "'s :params-schema is malformed: the "
               "validator threw, so its params could not be checked. "
               "Per Spec 010 §Per-step recovery.")
          (str "mutation " mutation-id " params do not conform to "
               ":params-schema. Per Spec 016 §Mutations."))
        (assoc slots :mutation-id mutation-id)))))

(defn resolve-scope
  "The concrete execution scope for a mutation call: the payload `:scope`,
  else the spec's `:scope`, else `:rf.scope/global`. An absent scope fails
  open because a write has no cache-leak boundary of its own; a supplied
  scope is a ScopeInput resolved against `db` through the shared
  `resolve-scope-input` arm, and fails closed. Throws
  `:rf.error/resource-scope-unresolved-reference` when a supplied
  `{:from-db …}` resolves nil, `:rf.error/resource-scope-not-registered` for
  an unknown resolver, and rejects malformed concrete scopes, all before any
  mutation start. Spec 016 §Mutation scope is two distinct scopes (hybrid)."
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
