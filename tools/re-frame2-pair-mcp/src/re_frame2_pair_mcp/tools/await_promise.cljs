(ns re-frame2-pair-mcp.tools.await-promise
  "Shared browser-side Promise-await machinery.

  Two tools need the same capability: run a form over nREPL whose
  synchronous return is a JS Promise, then resolve to the Promise's
  eventual value rather than the `\"#object[Promise ...]\"` string a
  raw `pr-str` would produce. `eval-cljs :await` awaits a
  caller-supplied async form; `dispatch :await-render` awaits a
  render-settle Promise so `dispatch → observe` is a single
  deterministic step.

  The nREPL channel is request/response: the server sends a form and
  reads back the synchronous value. A Promise cannot ride back over
  that channel because it hasn't settled when the eval returns. So we
  use a **browser-side mailbox**:

    1. The caller-supplied form is wrapped so its evaluation:
       - if the result is a thenable, installs a mailbox entry on
         `js/globalThis.__rf2pair_await__`, chains `.then` / `.catch`
         to record the resolved value / rejection reason as `pr-str`
         text into that mailbox, and synchronously returns a
         `{:rf.mcp/await-mailbox <id>}` sentinel;
       - otherwise returns `{:rf.mcp/await-direct <v>}` (passthrough
         fast path — a non-thenable form never needed awaiting).
    2. On a mailbox sentinel the server polls the mailbox via a tiny
       second eval at a fast cadence until `:status` flips off
       `:pending`, or `:timeout-ms` elapses.

  This namespace owns ONLY the mailbox dance — `wrap-form`, the
  read/discard forms, the poll loop, and the sentinel dispatcher.
  Each consumer supplies its own envelope shape via the `on-resolved` /
  `on-rejected` / `on-timeout` / `on-missing` callbacks passed to
  [[handle-sentinel]] so the same plumbing yields tool-specific result
  vocabularies (`eval-cljs` returns `:value`; `dispatch :await-render`
  returns the dispatch envelope merged with `:settled? true`)."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]))

(def default-timeout-ms
  "Default deadline for awaiting a Promise return when the caller
  doesn't specify `:timeout-ms`. 5 seconds is generous for typical
  async work (layout, fetch, async fns, render-settle); pathologically
  long async forms can raise it. Mirrors shape with `tail-build`'s
  `:wait-ms`."
  5000)

(def ^:private mailbox-poll-ms
  "Cadence at which the server re-reads the mailbox after a thenable
  sentinel. 25ms = ~40 polls/sec — small enough to land within ~25ms
  of resolution, cheap enough on the nREPL socket to not flood it.
  The polling stops as soon as the mailbox status flips off
  `:pending`, so the cadence matters only for short-lived awaits."
  25)

(defn mailbox-key
  "The unique key written to `js/globalThis.__rf2pair_await__` for one
  await call. Random-uuid keeps concurrent awaits (a future N-agent
  workflow, or interleaved tool calls in a single host) from clobbering
  each other's mailboxes."
  []
  (str "await-" (random-uuid)))

(defn wrap-form
  "Wrap `form-str` so the browser-side eval:

    - evaluates the form (may return any value, thenable or not),
    - synchronously returns `{:rf.mcp/await-direct <v>}` for a
      non-thenable result (server fast-paths back to the value),
    - for a thenable result, installs a mailbox entry, chains
      `.then` / `.catch` to record the outcome, and synchronously
      returns `{:rf.mcp/await-mailbox <id>}`.

  All resolved/rejected values are `pr-str`'d into the mailbox so they
  survive the next `cljs-eval` round-trip as EDN text — the server
  reads them back unchanged.

  Uses `(some? (.-then v))` rather than `(instance? js/Promise v)` so
  any thenable (jQuery deferreds, axios responses, promise shims) is
  recognised — matching the JS-ecosystem `await` semantic."
  [form-str mailbox-id]
  (str
    "(let [user-fn# (fn [] " form-str ")"
    "      v# (user-fn#)"
    "      mailbox# (or (.-__rf2pair_await__ js/globalThis)"
    "                   (let [m# (cljs.core/js-obj)]"
    "                     (set! (.-__rf2pair_await__ js/globalThis) m#)"
    "                     m#))]"
    "  (if (and (some? v#)"
    "           (or (object? v#) (instance? js/Object v#))"
    "           (fn? (some-> v# .-then)))"
    "    (do"
    "      (aset mailbox# " (pr-str mailbox-id)
    "            (cljs.core/js-obj \"status\" \"pending\"))"
    "      (-> v#"
    "          (.then (fn [r#]"
    "                   (aset mailbox# " (pr-str mailbox-id)
    "                         (cljs.core/js-obj"
    "                           \"status\" \"resolved\""
    "                           \"value\" (cljs.core/pr-str r#)))))"
    "          (.catch (fn [e#]"
    "                    (aset mailbox# " (pr-str mailbox-id)
    "                          (cljs.core/js-obj"
    "                            \"status\" \"rejected\""
    "                            \"rejection\" (cljs.core/pr-str e#))))))"
    "      {:rf.mcp/await-mailbox " (pr-str mailbox-id) "})"
    "    {:rf.mcp/await-direct v#}))"))

(defn- read-mailbox-form
  "Read + clear the mailbox entry for `mailbox-id`. Resolves to one of:

    - `{:status :pending}`               — promise hasn't settled yet
    - `{:status :resolved :value v}`     — value is the post-pr-str EDN
    - `{:status :rejected :rejection s}` — s is pr-str of the rejection
    - `{:status :missing}`               — mailbox slot is gone (a wire-
      shape regression; surfaced rather than silently retried)

  Cleared on a non-pending read so a repeated poll after resolution
  doesn't re-read stale data."
  [mailbox-id]
  (str
    "(let [mailbox# (or (.-__rf2pair_await__ js/globalThis) (cljs.core/js-obj))"
    "      entry#   (aget mailbox# " (pr-str mailbox-id) ")]"
    "  (cond"
    "    (nil? entry#)"
    "    {:status :missing}"
    "    (= \"pending\" (aget entry# \"status\"))"
    "    {:status :pending}"
    "    (= \"resolved\" (aget entry# \"status\"))"
    "    (let [v# (cljs.reader/read-string (aget entry# \"value\"))]"
    "      (js-delete mailbox# " (pr-str mailbox-id) ")"
    "      {:status :resolved :value v#})"
    "    (= \"rejected\" (aget entry# \"status\"))"
    "    (let [s# (aget entry# \"rejection\")]"
    "      (js-delete mailbox# " (pr-str mailbox-id) ")"
    "      {:status :rejected :rejection s#})"
    "    :else"
    "    {:status :missing}))"))

(defn- discard-mailbox-form
  "Best-effort drop of the mailbox slot — called after a timeout so a
  late resolution doesn't pile up garbage on `js/globalThis`. Errors
  are swallowed; the timeout result is already on its way back."
  [mailbox-id]
  (str
    "(when-let [mailbox# (.-__rf2pair_await__ js/globalThis)]"
    "  (js-delete mailbox# " (pr-str mailbox-id) ")"
    "  nil)"))

(defn poll-mailbox!
  "Poll the mailbox at `mailbox-poll-ms` cadence until status is non-
  `:pending` or `timeout-ms` elapses. Resolves to the final mailbox
  read result map (`:resolved` / `:rejected` / `:timeout` / `:missing`).
  On `:timeout`, fires-and-forgets a discard-form so the late resolution
  doesn't accumulate."
  [conn build-id mailbox-id timeout-ms]
  (let [start     (js/Date.now)
        read-form (read-mailbox-form mailbox-id)]
    (js/Promise.
      (fn [resolve _reject]
        (letfn [(poll []
                  (let [elapsed (- (js/Date.now) start)]
                    (if (>= elapsed timeout-ms)
                      (do
                        ;; Fire-and-forget discard; ignore any error.
                        (-> (nrepl/cljs-eval-value conn build-id
                                                   (discard-mailbox-form mailbox-id))
                            (.catch (fn [_] nil)))
                        (resolve {:status :timeout}))
                      (-> (nrepl/cljs-eval-value conn build-id read-form)
                          (.then (fn [r]
                                   (if (and (map? r) (= :pending (:status r)))
                                     (js/setTimeout poll mailbox-poll-ms)
                                     (resolve (if (map? r) r {:status :missing})))))
                          (.catch (fn [_]
                                    ;; Transient read failure — keep polling
                                    ;; until timeout. Reading the mailbox is
                                    ;; cheap; one hiccup shouldn't abort the
                                    ;; await.
                                    (js/setTimeout poll mailbox-poll-ms)))))))]
          (poll))))))

(defn handle-sentinel
  "Inspect the wrapper's synchronous return and dispatch.

  - `:rf.mcp/await-direct` map → the form's value verbatim (non-
    thenable fast path); calls `(on-resolved v)`.
  - `:rf.mcp/await-mailbox` map → kicks off the polling loop, then
    routes the final status to the matching callback.
  - anything else → a regression in `wrap-form`; calls `(on-missing
    {:sentinel <pr-str>})`.

  The four callbacks (`:on-resolved` `:on-rejected` `:on-timeout`
  `:on-missing`) each take a map and return the consumer's wire
  envelope. This is the single seam that keeps the mailbox plumbing
  tool-agnostic — `eval-cljs` and `dispatch :await-render` plug in
  their own result vocabularies. Each callback is invoked with:

    :on-resolved {:value <edn-value>}
    :on-rejected {:rejection <pr-str-of-rejection>}
    :on-timeout  {:timeout-ms n}
    :on-missing  {:reason <:missing | :bad-sentinel> :sentinel <pr-str?>}

  Returns a Promise of the chosen callback's return value."
  [conn build-id timeout-ms sentinel
   {:keys [on-resolved on-rejected on-timeout on-missing]}]
  (cond
    (and (map? sentinel) (contains? sentinel :rf.mcp/await-direct))
    (js/Promise.resolve (on-resolved {:value (:rf.mcp/await-direct sentinel)}))

    (and (map? sentinel) (contains? sentinel :rf.mcp/await-mailbox))
    (-> (poll-mailbox! conn build-id (:rf.mcp/await-mailbox sentinel) timeout-ms)
        (.then (fn [result]
                 (case (:status result)
                   :resolved (on-resolved {:value (:value result)})
                   :rejected (on-rejected {:rejection (:rejection result)})
                   :timeout  (on-timeout {:timeout-ms timeout-ms})
                   ;; :missing — defensive; should not happen in practice.
                   (on-missing {:reason :missing})))))

    :else
    (js/Promise.resolve
      (on-missing {:reason :bad-sentinel :sentinel (pr-str sentinel)}))))
