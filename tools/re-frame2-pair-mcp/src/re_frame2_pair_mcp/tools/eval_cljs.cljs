(ns re-frame2-pair-mcp.tools.eval-cljs
  "Tool: eval-cljs — evaluate one CLJS form.

  ## Launch-flag gate

  The eval-cljs tool is the REPL primitive of a pair-debug session —
  arbitrary form evaluation against the live re-frame2 runtime is the
  whole reason an operator installs re-frame2-pair-mcp. Published
  builds default this surface **ON**; the operator opts OUT via
  `--no-eval` at server launch for the rare paranoid case (CI runs,
  shared dev environments where multiple humans share a single MCP
  process).

  ### Threat-model rationale

  A default-OFF eval gate would parallel `--allow-writes` in shape but
  not in effect. `--allow-writes` is load-bearing because
  pair-tool writes can confuse the debug audit trail (\"did my app
  produce this state change, or did the pair tool?\"). An eval gate
  does NOT parallel that protection: eval-cljs can express any write
  the writes-gate would block. The two gates are not independent —
  once eval is on, writes are de-facto on. So a default-OFF eval
  surface would add friction without adding a separable protection,
  which is why this surface defaults ON.

  The real defence is **don't expose this MCP to untrusted callers**.
  Once an operator has installed re-frame2-pair-mcp and wired it into
  `~/.claude.json`, they've already declared trust in the surface.

  ### Implementation

  The gate is a single atom (`eval-allowed?`) set by
  `server.cljs/main` from `process.argv` before the dispatcher starts
  handling tools/call requests. The atom defaults to `true`; passing
  `--no-eval` flips it to `false`. Tests flip the atom directly via
  `set-eval-allowed!`.

  ## Opt-in Promise awaiting

  `cljs-eval` captures the synchronous return value of the form and
  `pr-str`'s it. When the form returns a JS Promise — any async work
  (`fetch`, `.layout()`, `async` fns, anything chained with `.then`) —
  the synchronous return IS the Promise object; `pr-str` produces a
  `\"#object[Promise ...]\"` string that says \"I'm a Promise\" with no
  access to the eventually-resolved value. Resolving such a value by
  hand is a two-call mailbox dance (stash on `js/window`, return a
  sentinel, read the global on a second call, repeat-poll until
  resolved).

  When the caller passes `:await true`, the server automates that
  dance:

    1. The user's form is wrapped browser-side. The wrapper evaluates
       the form; if the result is a thenable, it stashes a mailbox
       entry on `js/globalThis.__rf2pair_await__` and chains
       `.then` / `.catch` to record the resolved value or rejection
       reason as `pr-str` text into that mailbox.
    2. The wrapper returns immediately. Its synchronous value is a
       sentinel — either `{:rf.mcp/await-direct <v>}` for a
       non-thenable form (passthrough) or
       `{:rf.mcp/await-mailbox <id>}` for a thenable.
    3. On a mailbox sentinel, the server polls the mailbox via a tiny
       second eval (`read-and-clear-mailbox`) at a fast cadence until
       `:status` flips off `:pending`, or `:timeout-ms` elapses.
    4. `:resolved` → `{:ok? true :value <edn-value> :build ...}`.
    5. `:rejected` →
       `{:ok? false :reason :rf.error/eval-cljs-rejected
         :rejection \"<pr-str of rejection>\" ...}`.
    6. Timeout →
       `{:ok? false :reason :rf.error/eval-cljs-timeout
         :timeout-ms n ...}`.

  ### Why opt-in (not always-on)

  Two principled reasons (the back-compat argument doesn't bind in
  pre-alpha):

    - **Promise pass-through is sometimes intentional**. Some forms
      deliberately return a Promise object to hand off to other code;
      auto-awaiting would change the contract for those callers.
    - **Timeout policy should be caller-controlled** rather than
      implicit. The caller knows whether their async work is a 5ms
      `Promise.resolve` or a multi-second layout computation; the
      server shouldn't pick.

  The default `:await false` keeps the synchronous semantics — the
  form's synchronous return is `pr-str`'d and returned verbatim. Callers
  who DO want to wait on a Promise opt in explicitly and pick the
  deadline.

  ## Frame targeting

  `eval-cljs`, like every other structured op (`dispatch`, `snapshot`,
  `get-path`, `trace-window`, `watch-epochs`, `subscribe`,
  `replace-app-db`), accepts an optional `:frame` arg targeting a named
  frame.

  EP-0002 — with no `:frame` arg the form carries NO frame
  stamp, so a frame-scoped op inside it (`rf/subscribe` / `rf/dispatch` /
  `rf/current-frame-id`) raises the always-on `:rf.error/no-frame-context`
  rather than silently targeting a synthesised `:rf/default` (`:rf/default`
  is an ordinary id, never an absence-repair fallback — Spec 002 §Frame
  target resolution). Supply `:frame` to name the target explicitly. The
  agent-facing rule: a frame-scoped `eval-cljs` form MUST carry `:frame`.

  When `:frame :rf/xray` is supplied, the form is wrapped server-side
  in `(re-frame.core/with-frame :rf/xray <user-form>)` before being
  sent over nREPL. `with-frame` is the framework's lexical frame-
  binding macro (per Spec 002 §with-frame) — `*current-frame*` is
  bound to the named frame for the form's dynamic extent, so any
  `(rf/subscribe ...)` / `(rf/dispatch ...)` / `(rf/current-frame-id)`
  inside the form resolves against the requested frame.

  The wrap composes orthogonally with `:await true`: a Promise-
  returning form wrapped in `with-frame` is still a Promise; the
  await mailbox dance proceeds normally. Frame binding only lasts
  for the form's synchronous evaluation — once the Promise resolves
  on a later tick, the original lexical frame is gone (this matches
  the macro's contract per Spec 002, where async closures must
  capture a `capture-frame` (or wrap via `frame-bound-fn` /
  `frame-bound-fn*`) so the captured ops survive the boundary).

  ## Mailbox machinery

  The browser-side Promise mailbox dance (`wrap-form`, the read/poll
  forms, the sentinel dispatcher) lives in
  `re-frame2-pair-mcp.tools.await-promise` so `dispatch :await-render`
  reuses the SAME proven plumbing. This ns supplies only the
  eval-cljs result vocabulary (`:value` / `:rf.error/eval-cljs-*`) via
  the callback seam `await-promise/handle-sentinel` exposes."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.await-promise :as await-promise]
            [re-frame2-pair-mcp.tools.result-envelope :as renv]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defonce ^:private eval-allowed?
  ;; Default ON in published builds. `server.cljs/main` flips this to
  ;; `false` when `--no-eval` is present in `process.argv`.
  (atom true))

(defn set-eval-allowed!
  "Set the eval-cljs launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! eval-allowed? (boolean enabled?)))

(defn eval-allowed-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @eval-allowed?)

;; ---------------------------------------------------------------------------
;; Await mode — wrap the user's form to detect thenables + automate the
;; mailbox dance. The mailbox plumbing lives in
;; `await-promise`; this ns supplies only the eval-cljs
;; result vocabulary via the callback seam.
;; ---------------------------------------------------------------------------

(defn- sentinel-callbacks
  "The eval-cljs result vocabulary for `await-promise/handle-sentinel`.
  `resolved-build` is the build the eval ran against; echoed back on
  every envelope so the caller's auto-detect path is visible."
  [resolved-build timeout-ms]
  {:on-resolved
   (fn [{:keys [value]}]
     (wire/ok-text {:ok?   true
                    :value value
                    :build resolved-build}))

   :on-rejected
   (fn [{:keys [rejection]}]
     (wire/ok-text {:ok?       false
                    :reason    :rf.error/eval-cljs-rejected
                    :rejection rejection
                    :build     resolved-build}))

   :on-timeout
   (fn [_]
     (wire/ok-text {:ok?        false
                    :reason     :rf.error/eval-cljs-timeout
                    :timeout-ms timeout-ms
                    :build      resolved-build}))

   :on-missing
   (fn [{:keys [reason sentinel]}]
     (if (= :bad-sentinel reason)
       (wire/ok-text {:ok?      false
                      :reason   :rf.error/eval-cljs-await-wrap-failed
                      :hint     "the await wrapper returned an unrecognised sentinel"
                      :sentinel sentinel
                      :build    resolved-build})
       (wire/ok-text {:ok?    false
                      :reason :rf.error/eval-cljs-mailbox-missing
                      :hint   (str "eval-cljs await mailbox vanished before the result was read. "
                                   "This indicates a wire-shape regression or a page-reload "
                                   "destroying the mailbox between the wrap and the poll.")
                      :build  resolved-build})))})

;; ---------------------------------------------------------------------------
;; Tool entry point.
;; ---------------------------------------------------------------------------

(defn- wrap-in-frame
  "Wrap `form-str` in `(re-frame.core/with-frame <frame-kw> <form>)`.
  Returns the wrapped source verbatim — callers feed
  this into the await wrapper or send it straight over nREPL.

  `frame-kw` is emitted as an EDN literal via `pr-str` so a kebab-case
  / namespaced keyword survives the round-trip without quoting
  surprises. `with-frame` is the framework's lexical frame-binding
  macro (Spec 002 §with-frame); per its contract, async closures
  inside the form must capture a `capture-frame` (or wrap via
  `frame-bound-fn` / `frame-bound-fn*`) for the binding to survive
  later ticks. We document that asymmetry in the ns docstring rather
  than enforcing it here — the body is opaque user-supplied source."
  [form-str frame-kw]
  (str "(re-frame.core/with-frame " (pr-str frame-kw) " " form-str ")"))

(defn eval-cljs-tool [conn args]
  (let [form       (wire/arg args :form)
        build-id   (wire/arg-build conn args)
        explicit?  (wire/arg-build-explicit? conn args)
        await?     (boolean (wire/arg args :await))
        ;; Validate `:timeout-ms` as a positive-millisecond
        ;; integer BEFORE it threads into `await-promise/poll-mailbox!`'s
        ;; `(>= elapsed timeout-ms)` deadline. A non-numeric value
        ;; (`"never"`) makes `(>= n NaN)` never true ⇒ the mailbox poll
        ;; loop runs FOREVER on a pending await; a zero / negative value
        ;; times out immediately. Absent ⇒ the documented default.
        timeout-r  (args/parse-timeout-arg "timeout-ms" (wire/arg args :timeout-ms))
        timeout-ms (or (second timeout-r) await-promise/default-timeout-ms)
        ;; Optional `:frame` arg targets a named frame for
        ;; the form's lexical scope. Same coercion as dispatch/
        ;; snapshot/get-path: bare names (\"rf/default\") and EDN-
        ;; shaped strings (\":rf/default\") both accepted.
        frame      (some-> (wire/arg args :frame) args/->frame-keyword)]
    (cond
      (= :err (first timeout-r))
      (js/Promise.resolve (wire/err-text (second timeout-r)))

      (not @eval-allowed?)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :rf.error/eval-cljs-disabled
           :hint   (str "eval-cljs has been disabled for this server "
                        "instance via --no-eval; relaunch without that "
                        "flag to enable.")}))

      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-form
                        :hint "usage: eval-cljs {form '<cljs-form>' [build :app] [await true] [timeout-ms 5000] [frame :rf/xray]}"}))

      :else
      ;; Resolve the build (auto-detect the single running one
      ;; when no explicit :build was passed) and confirm a live runtime
      ;; BEFORE eval'ing. Never emit `:ok? true :value nil` for a build
      ;; with no runtime — that's indistinguishable from a genuine nil.
      (let [;; Apply the with-frame wrap before await
            ;; wrapping. The await wrap operates on whatever source
            ;; we send over the wire; with-frame is just additional
            ;; outer-CLJS so it composes orthogonally with await.
            user-form (if frame (wrap-in-frame form frame) form)]
        (-> (probe/resolve-and-preflight! conn build-id explicit?)
            (.then (fn [resolved-build]
                     (if-not await?
                       ;; Default path — wrap the form in the typed
                       ;; result codec so a genuine nil, an
                       ;; unresolved-symbol throw, and an unserializable
                       ;; value are DISTINCT tagged outcomes instead of
                       ;; collapsing to a bare nil. The runtime
                       ;; classifies; the server projects via
                       ;; `renv/envelope->result`. (`:await true` keeps
                       ;; the Promise-mailbox path — a thenable form
                       ;; needs the await dance, not the sync classify.)
                       (let [wrapped (renv/wrap-form user-form)]
                         (-> (nrepl/cljs-eval-value conn resolved-build wrapped)
                             (.then
                               (fn [v]
                                 (let [result
                                       (renv/envelope->result
                                         v
                                         (fn [genuine]
                                           (cond-> {:ok?   true
                                                    :value genuine
                                                    :build resolved-build}
                                             frame (assoc :frame frame))))
                                       result (if (renv/error? result)
                                                (cond-> (assoc result :build resolved-build)
                                                  frame (assoc :frame frame))
                                                result)]
                                   (if (renv/error? result)
                                     (wire/err-text result)
                                     (wire/ok-text result)))))))
                       ;; Await path — wrap the form, dispatch
                       ;; on the sentinel, poll the mailbox if needed. The
                       ;; mailbox plumbing lives in `await-promise`;
                       ;; we supply the eval-cljs result
                       ;; vocabulary via the callback seam.
                       (let [mailbox-id (await-promise/mailbox-key)
                             wrapped    (await-promise/wrap-form user-form mailbox-id)]
                         (-> (nrepl/cljs-eval-value conn resolved-build wrapped)
                             (.then (fn [sentinel]
                                      (await-promise/handle-sentinel
                                        conn resolved-build timeout-ms sentinel
                                        (sentinel-callbacks resolved-build timeout-ms)))))))))
            (.catch
              (fn [err]
                ;; A compile warning/error (unresolved symbol,
                ;; syntax/arity error) rejects with a structured
                ;; `:rf.error/eval-cljs-compile-error` ex-info from
                ;; `cljs-eval-value`. Surface it as a proper `:isError`
                ;; envelope (matching the typed eval-error path above), so
                ;; the agent never reads it as a value — echoing the
                ;; resolved build / frame for context. Every other
                ;; rejection (transport, preflight) keeps the existing
                ;; `err->result` projection.
                (let [data (ex-data err)]
                  (if (= :rf.error/eval-cljs-compile-error (:reason data))
                    (wire/err-text
                      (cond-> (merge {:ok? false} data {:build build-id})
                        frame (assoc :frame frame)))
                    (probe/err->result :eval-error err))))))))))
