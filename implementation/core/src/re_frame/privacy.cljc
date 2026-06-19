(ns re-frame.privacy
  "Privacy policy helpers.

  Frame-owned classification is the canonical durable app-db privacy
  declaration (EP-0015 §3 / §8): `(rf/reg-frame :app {:sensitive {:app-db
  [[:auth :token]]}})` feeds the per-frame sensitive-declarations registry
  (`[:rf.runtime/elision :sensitive-declarations]`, installed by
  `re-frame.frame-classification`), and the router installs an internal
  redaction interceptor for matching path-scoped handlers. Sensitivity is a
  property of the data VALUE at a path, not of the handler that touched it.

  EP-0015 §8: schemas describe shape, not durable app-db egress policy — a
  `reg-app-schema` `{:sensitive? true}` slot prop is no longer a route into
  this registry. (Schema `:sensitive?` still drives schema-validation-
  failure-trace redaction in `re-frame.schemas`, a separate egress product.)

  The helpers here are the path-overlap arm of that scheme — they redact
  event-payload paths whose path-scoped handler slice overlaps a
  frame-declared sensitive app-db path."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def redacted-sentinel
  :rf/redacted)

(defn sensitive?
  [trace-event]
  (and (map? trace-event)
       (true? (:sensitive? trace-event))))

(defn- path-prefix?
  [prefix path]
  (let [prefix (vec prefix)
        path   (vec path)]
    (and (<= (count prefix) (count path))
         (= prefix (subvec path 0 (count prefix))))))

(defn- relative-path
  [prefix path]
  (subvec (vec path) (count (vec prefix))))

(defn- handler-db-paths
  [interceptors]
  (into []
        (comp (filter map?)
              (keep :path))
        interceptors))

(defn- sensitive-declarations
  [frame-id]
  ;; rf2-ivr38u — HOT PATH (every dispatch, via `schema-redaction-paths`
  ;; in the router's `prepare-handler-ctx`). The
  ;; `:elision/sensitive-declarations` hook is published ONCE at boot
  ;; (`re-frame.elision`, bottom of file) and never withdrawn in
  ;; production — exactly the `get-fn-cached` sticky profile. Cached
  ;; resolution avoids re-deref'ing the global `hooks` atom + re-walking
  ;; its map on every dispatch; the cache invalidates on hook re-publish
  ;; (`set-fn!`), so dev-time `(require 're-frame.elision :reload)` still
  ;; swaps the resolved fn on the next dispatch.
  (if-let [f (late-bind/get-fn-cached :elision/sensitive-declarations)]
    (f frame-id)
    {}))

(defn- overlap-redaction-paths
  "Given the handler chain's app-db `:path` slices and the frame's
  declared sensitive app-db paths, return the distinct event-payload
  paths whose path-scoped handler slice overlaps a sensitive path."
  [db-paths sensitive-paths]
  (vec
    (distinct
      (mapcat
        (fn [db-path]
          (keep (fn [sensitive-path]
                  (when (path-prefix? db-path sensitive-path)
                    (relative-path db-path sensitive-path)))
                sensitive-paths))
        db-paths))))

(defn schema-redaction-paths
  "Return event-payload paths that should be redacted for a handler
  whose interceptor chain focuses app-db through `path` interceptors.

  The overlap is computed against the frame's sensitive-declarations
  registry (frame-owned `:sensitive {:app-db …}` classification, EP-0015
  §3 / §8) — NOT against schema-attached `{:sensitive? true}` slot props,
  which no longer feed this registry. The fn name is retained for callers.

  rf2-ivr38u — DOMINANT-PATH SKIP: when the frame declares ZERO sensitive
  app-db paths (the common case) there is no overlap to compute, so the
  `handler-db-paths` chain walk + `mapcat` are bypassed entirely."
  [frame-id interceptors]
  (let [sensitive-paths (keys (sensitive-declarations frame-id))]
    (if (seq sensitive-paths)
      (overlap-redaction-paths (handler-db-paths interceptors) sensitive-paths)
      [])))

(defn- redact-path
  [payload path]
  (let [path (vec path)]
    (cond
      (empty? path)
      redacted-sentinel

      ;; rf2-agpv2.4 — the parent must be ASSOCIATIVE for `assoc-in` to
      ;; descend into it, not merely non-nil. A `(some? …)` guard let a
      ;; non-nil scalar parent through (e.g. payload `{:auth "tok"}` with
      ;; redact path `[:auth :password]`): `assoc-in` then recurses into
      ;; the string and throws ("cannot assoc onto a String"). That throw
      ;; lands inside a `:before` interceptor and aborts the whole event
      ;; (classified `:rf.error/interceptor-exception`, no `:db` commit, no
      ;; `:fx`) — a redaction that silently drops the event. `associative?`
      ;; treats a non-associative parent as a no-op, matching the
      ;; missing-leaf no-op posture below.
      (associative? (get-in payload (butlast path)))
      (assoc-in payload path redacted-sentinel)

      :else
      payload)))

(defn redact-paths
  [payload paths]
  (reduce redact-path payload paths))

(defn redact-event
  "Redact the given payload paths in a conventional event vector.
  Non-map payload shapes pass through unchanged."
  [event paths]
  (if (and (vector? event)
           (>= (count event) 2)
           (map? (second event)))
    (let [[id payload & rest-args] event
          redacted-payload (redact-paths payload paths)]
      (into [id redacted-payload] rest-args))
    event))

(defn redacted-event-from-ctx
  [ctx]
  (or (:rf/redacted-event ctx)
      (interceptor/get-coeffect ctx :event)))

(defn schema-redaction-interceptor
  "Internal interceptor installed by the router for path-scoped handlers
  whose `:path` slice overlaps a frame-declared sensitive app-db path
  (EP-0015 §3 / §8 — frame-owned classification, not schema-attached). The
  handler body keeps the original `:event` coeffect; trace/error emit sites
  read `:rf/redacted-event`. The `:rf/schema-redaction` interceptor id is
  retained for wire compatibility."
  [paths]
  (let [paths (vec paths)]
    (interceptor/->interceptor*
      :id :rf/schema-redaction
      :before
      (fn [ctx]
        (assoc ctx :rf/redacted-event
               (redact-event (interceptor/get-coeffect ctx :event) paths))))))

;; ---- redact-interceptor — user-installed positional interceptor ----------------
;;
;; The positional `redact-interceptor` interceptor scrubs named payload keys
;; *before* the handler body runs. The handler sees the unredacted value
;; via the regular `:event` coeffect; the trace surface sees the redacted
;; version via `:rf/redacted-event`.
;;
;; The interceptor carries its `:paths` on the interceptor map itself so
;; the router can collect them at chain-assembly time and fold them into
;; the pre-chain `:run-start` / `emit-cascade-trailers` event projection
;; (which fires BEFORE any chain `:before` runs). The `:before` here remains
;; load-bearing for the in-chain composition with `:rf/schema-redaction`:
;; when both interceptors are present, this `:before` extends the already-
;; stashed `:rf/redacted-event` rather than overwriting it, so the union
;; of paths is scrubbed.

(def redact-interceptor-id :rf/redact-interceptor)

(defn redact-interceptor
  "Build a positional interceptor that overwrites the named keys in the
  event vector's payload map with the `:rf/redacted` sentinel before the
  handler body runs.

  REMOVED FROM THE PUBLIC API (EP-0015 §7, rf2-mngp4o). A positional
  \"redact for the trace but not the handler\" interceptor made privacy
  depend on interceptor placement rather than on the owner of the payload
  shape; registration-owned `:sensitive` payload classification +
  centralized `project-egress` at egress boundaries replace it. This fn
  remains as internal router plumbing (the router still recognises a
  resolved `redact-interceptor`-shaped value — `:id :rf/redact-interceptor`
  — in a handler's chain), but it is no longer published from the
  `re-frame.core` façade. Since EP-0022 (chains are reference-only) it can
  only reach a chain via `reg-interceptor` + a by-id reference, never as an
  inline value.

  The handler itself receives the UNREDACTED payload via the regular
  `:event` coeffect slot; the redaction is for the trace surface only
  (`:event/*` trace events, `:event/db-changed`, `:rf.error/handler-
  exception`, and the always-on error-emit substrate's record).

  `paths` is a sequence of `get-in`-style key paths into the payload map
  (the second element of the event vector, per the canonical M-19 map-
  payload form). A path that targets a missing leaf is a no-op; an empty
  path scrubs the whole payload to `:rf/redacted`. Non-map payload shapes
  pass through unchanged.

  Composition:
    - With frame-declared sensitive app-db paths on a path-scoped handler
      (EP-0015 §3 / §8) — additive. The router installs an internal
      redaction interceptor for the frame-declared overlapping paths; this
      user-installed interceptor extends (does not replace) the stashed
      `:rf/redacted-event` with its own paths.
    - With epoch `:redact-fn` — independent. The redact-fn runs at the
      assembled epoch-record boundary; this interceptor runs per
      handler invocation on the trace surface inside the cascade. The
      record carries the already-scrubbed trace events into the fn.

  Internal usage (no longer a public `rf/` surface; reference-only since
  EP-0022 — register the built value, reference it by id):

      (rf/reg-interceptor :auth/redact-login
        (privacy/redact-interceptor [[:password] [:token]]))

      (rf/reg-event :auth/login
        {:interceptors [:auth/redact-login]}
        (fn [{:keys [db]} [_ {:keys [username password token]}]]
          ;; password + token visible HERE (unredacted via :event coeffect)
          ;; trace surface sees them as :rf/redacted
          ...))

  Per [Spec 009 §Privacy](../../../../../spec/009-Instrumentation.md) and
  [Security.md §Behavioural MUSTs across the privacy surface](../../../../../spec/Security.md#behavioural-musts-across-the-privacy-surface)."
  [paths]
  (let [paths (vec paths)]
    (interceptor/->interceptor*
      :id     redact-interceptor-id
      ;; Paths are exposed on the interceptor map for chain-walking
      ;; consumers (router `prepare-handler-ctx` collects them so the
      ;; pre-chain `:run-start` trace event already carries the
      ;; redacted projection).
      :paths  paths
      :before
      (fn [ctx]
        (let [base    (or (:rf/redacted-event ctx)
                          (interceptor/get-coeffect ctx :event))
              scrubbed (redact-event base paths)]
          (assoc ctx :rf/redacted-event scrubbed))))))

(defn- redact-interceptor?
  [interceptor]
  (and (map? interceptor)
       (= redact-interceptor-id (:id interceptor))))

(defn user-redaction-paths
  "Walk an interceptor chain and return the concatenated `:paths` vectors
  of every `redact-interceptor` interceptor it contains.

  Read by the router at chain-assembly time so the pre-chain trace events
  (`:run-start`, `emit-cascade-trailers`'s `:run-end`) and the frame-
  classification emit-event projection both honour user-declared payload
  paths."
  [interceptors]
  (into []
        (comp (filter redact-interceptor?)
              (mapcat :paths))
        interceptors))

(defn collect-redaction-paths
  "Single-pass fusion (rf2-ivr38u / rf2-tgea2z companion) of
  `schema-redaction-paths` + `user-redaction-paths` over the SAME
  resolved interceptor chain — read together by the router's
  `prepare-handler-ctx` on every dispatch.

  Walks `interceptors` ONCE, collecting in one reduce both:
    * the handler chain's app-db `:path` slices (for the frame-declared
      sensitive-path overlap), and
    * the `redact-interceptor` `:paths` (user-declared payload paths).

  Returns `{:schema-paths <vec> :user-paths <vec>}`, behaviour-identical
  to calling `schema-redaction-paths` and `user-redaction-paths`
  separately. The schema-path overlap is only computed when the frame
  declares ≥1 sensitive app-db path (the dominant no-classification path
  skips the overlap `mapcat` entirely); the single chain walk still
  collects `:path` slices unconditionally so the overlap, when needed,
  sees the same db-paths the two-walk form did."
  [frame-id interceptors]
  (let [sensitive-paths (keys (sensitive-declarations frame-id))
        {:keys [db-paths user-paths]}
        (reduce (fn [acc interceptor]
                  (if (map? interceptor)
                    (cond-> acc
                      (some? (:path interceptor))
                      (update :db-paths conj (:path interceptor))

                      (= redact-interceptor-id (:id interceptor))
                      (update :user-paths into (:paths interceptor)))
                    acc))
                {:db-paths [] :user-paths []}
                interceptors)]
    {:schema-paths (if (seq sensitive-paths)
                     (overlap-redaction-paths db-paths sensitive-paths)
                     [])
     :user-paths   (vec user-paths)}))
