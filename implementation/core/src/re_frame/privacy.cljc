(ns re-frame.privacy
  "Privacy policy helpers.

  Durable app-db classification is declared via the EP-0025 commit-plane
  classification effects: a `reg-event` handler returns `:sensitive [[…]]`
  (or `:large` / `:clear-*`) alongside its `:db` write, and the router folds
  the declaration into the per-frame sensitive-declarations registry
  (`[:rf.runtime/elision :sensitive-declarations]`, written by
  `re-frame.elision/apply-classification-effects` under `:source :effect`).
  The router installs an internal redaction interceptor for matching
  path-scoped handlers. Sensitivity is a property of the data VALUE at a
  path, not of the handler that touched it.

  EP-0025: there is no durable `:sensitive` / `:large {:app-db …}` *frame
  annotation* — a frame is not app-db's definition site. The registry
  reader here is source-agnostic: it reads the union of every source that
  populates the slot (`:source :effect` commit-plane effects, `:source
  :flow` flow-output declarations, and subsystem projection-relative
  declarations).

  EP-0015 §8: schemas describe shape, not durable app-db egress policy — a
  `reg-app-schema` `{:sensitive? true}` slot prop is not a route into this
  registry. (Schema `:sensitive?` drives schema-validation-failure-trace
  redaction in `re-frame.schemas`, a separate egress product.)

  The helpers here are the path-overlap arm of that scheme — they redact
  event-payload paths whose path-scoped handler slice overlaps a
  classified sensitive app-db path."
  (:require [re-frame.interceptor :as rf.interceptor]
            [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def redacted-sentinel
  :rf/redacted)

(defn sensitive?
  "Does `trace-event` carry the top-level `:sensitive?` stamp? THE
  framework-published predicate every forwarder composes against, so the
  five-token check is written once (Spec 009 §Privacy).

  FAIL-CLOSED, and the truthiness is deliberate:

    | `:sensitive?`            | result |
    |--------------------------|--------|
    | `true`                   | true   |
    | `false` / `nil` / absent | false  |
    | any other truthy value   | true   |

  The `:rf/trace-event` schema types `:sensitive?` as a boolean, so a string
  `\"true\"`, a keyword `:yes` or a `1` is a CONTRACT VIOLATION — and the only
  safe reading of a violation on this axis is the conservative one. A
  `(true? …)` check reads every one of them as NOT sensitive and forwards the
  event, which is fail-OPEN in exactly the case where the producer has already
  proved itself unreliable. `boolean` is therefore the whole implementation:
  do not \"tighten\" it to `true?`.

  Matches the fail-closed posture `re-frame.mcp-base.sensitive/sensitive-stamp?`
  already applies on the MCP wire — that one additionally logs and counts the
  malformed stamp, which is envelope bookkeeping the framework predicate does
  not owe its callers."
  [trace-event]
  (and (map? trace-event)
       (boolean (:sensitive? trace-event))))

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
  ;; HOT PATH (every dispatch, via `schema-redaction-paths`
  ;; in the router's `prepare-handler-ctx`). The
  ;; `:elision/sensitive-declarations` hook is published ONCE at boot
  ;; (`re-frame.elision`, bottom of file) and never withdrawn in
  ;; production — exactly the `get-fn-cached` sticky profile. Cached
  ;; resolution avoids re-deref'ing the global `hooks` atom + re-walking
  ;; its map on every dispatch; the cache invalidates on hook re-publish
  ;; (`set-fn!`), so dev-time `(require 're-frame.elision :reload)` still
  ;; swaps the resolved fn on the next dispatch.
  (if-let [f (rf.late-bind/get-fn-cached :elision/sensitive-declarations)]
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
  registry (the union of every classification source — EP-0025 commit-plane
  effects, flow-output declarations, subsystem projection-relative
  declarations), the single source of truth for app-db sensitivity.

  DOMINANT-PATH SKIP: when the frame declares ZERO sensitive
  app-db paths (the common case) there is no overlap to compute, so the
  `handler-db-paths` chain walk + `mapcat` are bypassed entirely."
  [frame-id interceptors]
  (let [sensitive-paths (keys (sensitive-declarations frame-id))]
    (if (seq sensitive-paths)
      (overlap-redaction-paths (handler-db-paths interceptors) sensitive-paths)
      [])))

(defn- redact-path
  [payload path]
  (let [path   (vec path)
        parent (when (seq path) (get-in payload (pop path)))
        seg    (peek path)]
    (cond
      (empty? path)
      redacted-sentinel

      ;; The parent must be able to TAKE the leaf segment for `assoc-in` to
      ;; descend into it, not merely be non-nil or `associative?`. A scalar
      ;; parent (payload `{:auth "tok"}`, path `[:auth :password]`) throws
      ;; "cannot assoc onto a String", and so does a VECTOR parent under a
      ;; non-integer or out-of-range segment (payload `{:cards [{…}]}`, path
      ;; `[:cards :number]` — "Key must be integer"), even though a vector is
      ;; `associative?`. The overlap paths come from the DB's
      ;; declarations, not the payload's shape, and the router computes this
      ;; on every dispatch outside the chain's capture, so a throw here
      ;; escapes `dispatch-sync` and the event is lost. Anything that cannot
      ;; take the segment is the same no-op as a missing parent.
      (or (map? parent)
          (and (vector? parent) (integer? seg) (< -1 seg (count parent))))
      (assoc-in payload path redacted-sentinel)

      :else
      payload)))

(defn redact-paths
  [payload paths]
  (reduce redact-path payload paths))

(defn redact-event
  "Redact the given payload paths in a conventional event vector.

  Only the map payload form `[id {…} …]` is path-redactable: path-based
  redaction is map-key oriented, and only `(second event)` is scrubbed.

  SECURITY-RELEVANT — POSITIONAL ARGS EGRESS RAW. A secret carried in a
  POSITIONAL event arg — e.g. `[:auth/login \"user\" \"secret-token\"]` —
  is NOT redactable here and passes through unchanged into every trace and
  error sink (`:event/*`, `:event/db-changed`, `:rf.error/handler-exception`).
  A positional index has no declarable `:sensitive` path, so the fail-open
  EP-0025 model (unclassified ⇒ ships raw) cannot reach it. This is a KNOWN
  STRUCTURAL LIMITATION, not a bug. PREFER THE MAP PAYLOAD FORM for events
  carrying sensitive args — `[:auth/login {:user \"user\" :token \"…\"}]` —
  then classify the `:token` path so it redacts at egress."
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
      (rf.interceptor/get-coeffect ctx :event)))

(defn schema-redaction-interceptor
  "Internal interceptor installed by the router for path-scoped handlers
  whose `:path` slice overlaps a classified sensitive app-db path (EP-0025
  commit-plane classification, not schema-attached). The handler body keeps
  the original `:event` coeffect; trace/error emit sites read
  `:rf/redacted-event`. The `:rf/schema-redaction` interceptor id is
  retained for wire compatibility."
  [paths]
  (let [paths (vec paths)]
    (rf.interceptor/->interceptor*
      :id :rf/schema-redaction
      :before
      (fn [ctx]
        (assoc ctx :rf/redacted-event
               (redact-event (rf.interceptor/get-coeffect ctx :event) paths))))))
