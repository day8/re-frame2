(ns re-frame.resources.transport.http
  "The resources-owned lowering into managed HTTP (`:rf.http/managed`, the
  single built-in transport). The runtime supplies `:request-id`,
  `:on-success` and `:on-failure` from the attempt's work id and generation.
  The reply payloads carry `:work/id`, `:generation` and `:rf.frame/id` but
  no `:resource/key` or `:scope`: the reply handler resolves the scoped key
  through the receiving frame's ledger row, so it never rides the transport.
  The effect itself is registered by `re-frame.http.managed`.
  Spec 016 §Transport."
  (:require [re-frame.error :as rf.error]))

#?(:clj (set! *warn-on-reflection* true))

(def managed-http-fx
  "The fx-id the resource runtime emits for a managed-HTTP read.
  Spec 016 §Transport."
  :rf.http/managed)

(def succeeded-reply
  "The framework-internal success reply event id; user code MUST NOT dispatch
  it. Spec 016 §Transport."
  :rf.resource.internal/succeeded)

(def failed-reply
  "The framework-internal failure reply event id; user code MUST NOT dispatch
  it. Spec 016 §Transport."
  :rf.resource.internal/failed)

(def reserved-reply-keys
  "The reply-addressing keys the runtime owns and an app `:request` MUST NOT
  supply: an app-supplied reply target or request-id would bypass stale
  suppression. `:reply-to` is reserved with them because managed HTTP refuses
  it beside `:on-success` / `:on-failure` only at fx time, after the event has
  committed its in-flight entry. Spec 016 §Transport."
  #{:request-id :on-success :on-failure :reply-to})

(defn reject-reserved-reply-keys!
  "Returns `http-args` unchanged; throws
  `:rf.error/resource-reserved-request-key` when it supplies any
  `reserved-reply-keys`. `resource-key` names the attempt in the diagnostic,
  `where` the dispatch surface. Spec 016 §Transport."
  [http-args resource-key where]
  (let [offending-keys (filter #(contains? http-args %) reserved-reply-keys)]
    (when (seq offending-keys)
      (rf.error/throw-error!
        :rf.error/resource-reserved-request-key where
        (str "a resource :request supplied the "
             "runtime-owned reply-addressing key(s) "
             (pr-str (vec offending-keys))
             " — :request-id / :on-success / "
             ":on-failure are supplied by resource "
             "lowering from the attempt's work id "
             "and generation, and :reply-to "
             "cannot sit beside them. An app-supplied "
             "reply target bypasses stale suppression "
             "(the correctness boundary). Remove them "
             "from the :request return; a caller that "
             "wants a continuation passes :reply-to to "
             ":rf.resource/ensure or :rf.mutation/execute "
             "instead. Per Spec 016 §Transport.")
        {:recovery :fix-registration
         :extra    {:keys         (vec offending-keys)
                    :resource/key resource-key}}))
    http-args))

(defn build-managed-args
  "The `:rf.http/managed` args map: the app's `http-args` passed through
  unchanged (its `:decode` / `:accept` / `:retry` included) with the
  runtime-owned `:request-id`, `:on-success` and `:on-failure` assoc'd in.
  Rejects reserved keys first (`reject-reserved-reply-keys!`).

  Addressing is overridable so mutations share this lowering:
  `:on-success-id` / `:on-failure-id` default to the resource internal
  replies, and `:reply-payload` replaces the default `{:work/id :generation}`
  payload. `:rf.frame/id` is always merged into the payload so every reply
  can be frame-verified. Spec 016 §Transport."
  [{:keys [http-args request-id work-id frame-id generation where
           on-success-id on-failure-id reply-payload]
    resource-key :resource/key}]
  (reject-reserved-reply-keys! http-args resource-key
                               (or where 're-frame.resources.transport.http/build-managed-args))
  (let [payload (assoc (or reply-payload
                           {:work/id    work-id
                            :generation generation})
                       :rf.frame/id frame-id)]
    (assoc http-args
           :request-id request-id
           :on-success [(or on-success-id succeeded-reply) payload]
           :on-failure [(or on-failure-id failed-reply) payload])))

(defn lower
  "Returns the fx pair `[:rf.http/managed args]` for a resource ensure or
  refetch, built from `ensure-ctx` by `build-managed-args`. Its
  `:resource/key` is for diagnostics only and never reaches the payload.
  Spec 016 §Transport."
  [ensure-ctx]
  [managed-http-fx (build-managed-args ensure-ctx)])
