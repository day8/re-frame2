(ns re-frame.resources.transport.http
  "The managed-HTTP read transport for resources — the single built-in
  transport in the initial scope. Per Spec 016 §Transport.

  The resource runtime first creates or joins a work-ledger record, then
  lowers an ensure/refetch into managed HTTP (`:rf.http/managed`, Spec
  014), supplying `:request-id` / `:on-success` / `:on-failure` itself
  from the scoped resource key and current generation:

      [:rf.http/managed
       (assoc http-args
              :request-id  request-id
              :on-success  [:rf.resource.internal/succeeded
                            {:work-id … :resource-key … :scope …
                             :rf.frame/id … :generation …}]
              :on-failure  [:rf.resource.internal/failed
                            {:work-id … :resource-key … :scope …
                             :rf.frame/id … :generation …}])]

  The managed-HTTP artefact (`day8/re-frame2-http`, `re-frame.http-managed`)
  is reached LATE-BOUND — resources never statically `:require`s it, so an
  app that loads resources but issues no HTTP-backed reads (or supplies a
  later transport) does not drag the HTTP body. The runtime emits the
  `:rf.http/managed` fx through the ordinary effect path; this namespace
  builds the args map (request-id + reply addressing).

  SKELETON slice (rf2-p10npe): the lowering shape is named; the live
  request-id minting + work-ledger correlation land with the managed-HTTP
  + runtime slices."
  (:require [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def managed-http-fx
  "The reserved fx-id the resource runtime emits to issue a managed-HTTP
  read (`:rf.http/managed`, Spec 014). Per Spec 016 §Transport."
  :rf.http/managed)

(def succeeded-reply
  "The framework-internal success reply event id. Per Spec 016 §Events
  (the internal replies) — user code MUST NOT dispatch it."
  :rf.resource.internal/succeeded)

(def failed-reply
  "The framework-internal failure reply event id. Per Spec 016 §Events —
  user code MUST NOT dispatch it."
  :rf.resource.internal/failed)

(defn build-managed-args
  "Build the `:rf.http/managed` args map for a resource ensure/refetch:
  the resource's `:request` (a Spec 014 managed-HTTP args map) with the
  runtime-owned `:request-id` and the `:on-success` / `:on-failure`
  internal reply addressing assoc'd in. Per Spec 016 §Transport.

  The reply payloads stamp the qualified `:rf.frame/id` (the canonical
  carried frame stamp, EP-0002 R3) — matching the `:work/frame` stamp on
  the ledger record — plus `:work-id`, `:resource-key`, `:scope`, and
  `:generation` so the reply handlers can verify frame + work-id +
  generation before writing (stale suppression is the correctness
  boundary). An app `:request` that supplies `:request-id` / `:on-success`
  / `:on-failure` itself is rejected (it would bypass stale suppression).

  SKELETON: builds the args shape; the live request-id + work correlation
  are supplied by the runtime slice via `ensure-ctx`."
  [{:keys [http-args request-id work-id resource-key scope frame-id generation]}]
  (let [reply-payload {:work-id      work-id
                       :resource-key resource-key
                       :scope        scope
                       :rf.frame/id  frame-id
                       :generation   generation}]
    (assoc http-args
           :request-id request-id
           :on-success [succeeded-reply reply-payload]
           :on-failure [failed-reply reply-payload])))

(defn lower
  "Lower a resource ensure/refetch into managed HTTP. Builds the
  `:rf.http/managed` args (`build-managed-args`) and returns the fx pair
  `[:rf.http/managed args]` the runtime threads into its `:fx` vector.

  Per Spec 016 §Transport. The managed-HTTP artefact is reached
  late-bound (a require-time check) so resources carries no static dep on
  it; an app that omits `day8/re-frame2-http` while issuing an HTTP-backed
  resource read gets a structured artefact-missing error rather than an
  opaque no-handler.

  SKELETON slice (rf2-p10npe): returns the fx-pair SHAPE from the
  ensure-context; the live ensure-context (request-id, work-id, scoped
  key, generation) is assembled by the runtime slice (rf2-pbxj48). Until
  the runtime lands, calling this directly with an incomplete ctx is a
  programming error."
  [ensure-ctx]
  ;; Defense-in-depth: surface the managed-HTTP feature presence through
  ;; the always-published feature probe (consult ≠ static require). The
  ;; runtime slice will use this to fail-closed with a clear
  ;; artefact-missing error when an HTTP-backed read is issued without
  ;; the HTTP artefact on the classpath.
  (when-not (late-bind/get-fn :http/abort-on-actor-destroy)
    (throw (ex-info ":rf.error/http-artefact-missing"
                    {:rf.error/id :rf.error/http-artefact-missing
                     :where       're-frame.resources.transport.http/lower
                     :recovery    :no-recovery
                     :reason      (str "an HTTP-backed resource read requires "
                                       "day8/re-frame2-http on the classpath; "
                                       "add it to deps and require "
                                       "re-frame.http-managed at app boot.")})))
  [managed-http-fx (build-managed-args ensure-ctx)])
