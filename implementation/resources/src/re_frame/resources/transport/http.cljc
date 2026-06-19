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
                            {:work/id … :resource/key … :scope …
                             :rf.frame/id … :generation …}]
              :on-failure  [:rf.resource.internal/failed
                            {:work/id … :resource/key … :scope …
                             :rf.frame/id … :generation …}])]

  The verification-payload work identity is `:work/id` — the qualified
  spelling the ledger row, the entry's `:current-work`, and the uniform
  reply envelope all use (EP-0007 one-attempt-one-spelling: one attempt has
  one work id, one name). It is NOT `:work-id`.

  The managed-HTTP artefact (`day8/re-frame2-http`, `re-frame.http.managed`)
  is reached LATE-BOUND — resources never statically `:require`s it, so an
  app that loads resources but issues no HTTP-backed reads (or supplies a
  later transport) does not drag the HTTP body. The runtime emits the
  `:rf.http/managed` fx through the ordinary effect path; this namespace
  builds the args map (request-id + reply addressing).

  The lowering ships here: `lower` mints the request-id and stamps the
  work-ledger correlation (`:work/id` / `:resource/key` / `:scope` /
  `:rf.frame/id` / `:generation`) into the reply addressing."
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]))

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

(def reserved-reply-keys
  "The reply-addressing / request-correlation keys the RUNTIME owns and an
  app `:request` (the Spec 014 managed-HTTP args map) MUST NOT supply. Per
  Spec 016 §Transport: the runtime supplies `:request-id` / `:on-success` /
  `:on-failure` from the scoped resource key and current generation; an app
  that supplies them itself could bypass stale-suppression (point a reply
  at an event other than the verifying internal handler, or reuse a
  request-id the runtime correlates work by). Rejected loudly."
  #{:request-id :on-success :on-failure})

(defn reject-reserved-reply-keys!
  "Reject an app `:request` (the Spec 014 managed-HTTP args map) that
  supplies any runtime-owned reply-addressing key (`reserved-reply-keys`).
  Throws `:rf.error/resource-reserved-request-key`. Per Spec 016 §Transport
  (\"an app `:request` that bypasses stale-suppression by supplying
  `:request-id` / `:on-success` / `:on-failure` is rejected\"). `where`
  names the dispatch surface; `resource-key` carries the scoped key for the
  diagnostic. Returns `http-args` unchanged when it conforms."
  [http-args resource-key where]
  (let [offending (filter #(contains? http-args %) reserved-reply-keys)]
    (when (seq offending)
      (error/throw-error!
        :rf.error/resource-reserved-request-key where
        (str "a resource :request supplied the "
             "runtime-owned reply-addressing key(s) "
             (pr-str (vec offending))
             " — :request-id / :on-success / "
             ":on-failure are supplied by resource "
             "lowering from the scoped resource key "
             "and current generation. An app-supplied "
             "reply target bypasses stale suppression "
             "(the correctness boundary). Remove them "
             "from the :request return. Per Spec 016 "
             "§Transport.")
        {:recovery :fix-registration
         :extra    {:keys         (vec offending)
                    :resource/key resource-key}}))
    http-args))

(defn build-managed-args
  "Build the `:rf.http/managed` args map for a resource ensure/refetch (or a
  mutation execute): the app `:request` (a Spec 014 managed-HTTP args map)
  with the runtime-owned `:request-id` and the `:on-success` / `:on-failure`
  internal reply addressing assoc'd in. Per Spec 016 §Transport.

  The reply payloads stamp the qualified `:rf.frame/id` (the canonical
  carried frame stamp, EP-0002 R3) — matching the `:work/frame` stamp on
  the ledger record — plus `:work/id`, `:resource/key`, `:scope`, and
  `:generation` so the reply handlers can verify frame + work-id +
  generation before writing (stale suppression is the correctness
  boundary). The verification work identity is `:work/id` (the qualified
  EP-0007 spelling the ledger / entry / reply envelope share), never the
  unqualified `:work-id`.

  Reply ADDRESSING is overridable so the SAME managed-HTTP lower serves
  both resources and mutations: `:on-success-id` / `:on-failure-id` default
  to the resource internal replies (`:rf.resource.internal/succeeded` /
  `:rf.resource.internal/failed`), and `:reply-payload` (when supplied)
  REPLACES the default resource verification payload (mutations stamp an
  instance id, not a resource scoped key). The frame stamp
  (`:rf.frame/id`) is always merged in so every reply carries the carried
  frame invariant.

  The app's `:request` return (`http-args`) — its top-level `:decode` /
  `:accept` / `:retry` and the nested `:request` envelope pass through
  UNCHANGED (transport retry belongs to managed HTTP). An app `:request`
  that supplies the runtime-owned `:request-id` / `:on-success` /
  `:on-failure` itself is REJECTED here (`reject-reserved-reply-keys!`) — it
  would bypass stale suppression."
  [{:keys [http-args request-id work-id scope frame-id generation where
           on-success-id on-failure-id reply-payload]
    resource-key :resource/key}]
  (reject-reserved-reply-keys! http-args resource-key
                               (or where 're-frame.resources.transport.http/build-managed-args))
  (let [payload (assoc (or reply-payload
                           ;; EP-0007: the verification work identity is the
                           ;; qualified `:work/id` (the ledger row / entry
                           ;; `:current-work` / reply-envelope spelling), one
                           ;; attempt one name.
                           {:work/id      work-id
                            :resource/key resource-key
                            :scope        scope
                            :generation   generation})
                       :rf.frame/id frame-id)]
    (assoc http-args
           :request-id request-id
           :on-success [(or on-success-id succeeded-reply) payload]
           :on-failure [(or on-failure-id failed-reply) payload])))

(defn lower
  "Lower a resource ensure/refetch into managed HTTP. Builds the
  `:rf.http/managed` args (`build-managed-args`) and returns the fx pair
  `[:rf.http/managed args]` the runtime threads into its `:fx` vector.

  Per Spec 016 §Transport. The managed-HTTP artefact is reached
  late-bound (a require-time check) so resources carries no static dep on
  it; an app that omits `day8/re-frame2-http` while issuing an HTTP-backed
  resource read gets a structured artefact-missing error rather than an
  opaque no-handler.

  `ensure-ctx` is the live ensure-context the runtime slice assembles:
  `:http-args` (the app `:request` return — a Spec 014 managed-HTTP args
  map), `:request-id`, `:work-id`, `:resource/key`, `:scope`, `:frame-id`,
  `:generation`, and `:where` (the dispatch surface for diagnostics). The
  built args carry the runtime-owned reply addressing; the app `:request`'s
  reserved reply keys are rejected (`build-managed-args`)."
  [ensure-ctx]
  ;; Defense-in-depth: surface the managed-HTTP feature presence through
  ;; the always-published feature probe (consult ≠ static require). This
  ;; fails closed with a clear artefact-missing error when an HTTP-backed
  ;; read is issued without the HTTP artefact on the classpath.
  (when-not (late-bind/get-fn :http/abort-on-actor-destroy)
    (error/throw-error!
      :rf.error/http-artefact-missing 're-frame.resources.transport.http/lower
      (str "an HTTP-backed resource read requires "
           "day8/re-frame2-http on the classpath; "
           "add it to deps and require "
           "re-frame.http.managed at app boot.")))
  [managed-http-fx (build-managed-args ensure-ctx)])
