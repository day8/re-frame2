(ns re-frame2-pair-mcp.tools.dispatch
  "Tool: dispatch — fire an event.

  ## Why parse the `event` arg as EDN (rf2-vflrg)

  The MCP `dispatch` surface is intentionally narrower than `eval-cljs`:
  the contract is `dispatch {event '[:ev/id ...]'}` — an EDN event
  vector, nothing else. An earlier shape inlined the caller's string
  verbatim into the generated eval form via `rt-raw`, which made the
  string a host-form expression rather than data. Any caller (or a
  prompt-injected agent) could supply arbitrary CLJS — `(println :pwn)`
  would have run — defeating the boundary that gates `eval-cljs`
  separately. Pre-alpha posture: this is gating the accident
  (\"I meant to dispatch an event, not execute code\") and the trivial
  malicious-string injection.

  Parsing the arg as EDN and requiring a `vector?` shape forces the
  payload to be data; it is then emitted into the runtime call through
  the normal `pr-str` path (`rt-call` arg). Unreadable strings and
  non-vector shapes return a structured `:reason :invalid-event-edn` /
  `:reason :not-an-event-vector` error rather than reaching the
  runtime.

  ## Render-settle — `:await-render` (rf2-gfu33)

  `pair-dispatch-sync!` returns once the handler has committed app-db,
  but the substrate (Reagent / the React spine) re-renders on a LATER
  tick — so \"dispatch then observe the DOM\" previously needed a manual
  `requestAnimationFrame` dance inside `eval-cljs`. The `:await-render`
  option makes `dispatch → observe` one deterministic step: the tool
  resolves only AFTER the substrate has flushed the new state to the
  DOM and the next paint has been scheduled.

  ### Substrate-agnostic flush via the adapter contract

  The flush is NOT a Reagent API call. The generated runtime form calls
  `re-frame.interop/after-render` — the framework's render-settle
  primitive, which routes through the `:adapter/after-render` late-bind
  hook (Spec 006 §Substrate adapter contract). Each adapter publishes
  its substrate-native impl: Reagent maps it to `r/after-render`
  (post-commit), the UIx / Helix spine to a `React.useLayoutEffect`-
  backed queue drain (post-commit / pre-paint, rf2-334d9), plain-atom /
  SSR to `next-tick`. `after-render` fires once the DOM reflects the new
  state; the form then chains ONE `requestAnimationFrame` so resolution
  lands at the paint boundary. The MCP server therefore stays
  substrate-agnostic — it never names Reagent, UIx, or Helix.

  ### Wire shape

  `:await-render` forces synchronous dispatch (the cascade must have
  committed before we can settle the render against the new state), so
  the result is the `pair-dispatch-sync!` envelope (`:cascade-summary`
  and friends) with `:mode :sync :settled? true` merged in. The form
  returns a browser-side Promise; the server awaits it through the
  shared `await-promise` mailbox (the same plumbing `eval-cljs :await`
  uses). A render-settle that doesn't complete within `:timeout-ms`
  (default 5000) returns `:reason :rf.error/dispatch-await-render-timeout`."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.await-promise :as await-promise]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- parse-event-edn
  "Parse the `event` MCP arg as EDN. Returns
  `[:ok parsed-vector]` on success or `[:err err-map]` on any failure.
  Two failure modes carry distinct reasons:

  - `:invalid-event-edn`     — `read-string` threw / returned nil.
  - `:not-an-event-vector`   — parsed cleanly but the shape is wrong
                                (e.g. a map, a symbol, a bare keyword).

  The hint repeats the documented usage shape so an agent that
  fat-fingers the call gets a corrective example without an extra
  round-trip."
  [event-str]
  (let [trimmed (some-> event-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err {:ok? false :reason :missing-event
             :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}]

      :else
      (let [parsed (try
                     (cljs.reader/read-string trimmed)
                     (catch :default e
                       ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok? false :reason :invalid-event-edn
                 :event event-str
                 :hint "event must be an EDN-readable vector, e.g. \"[:cart/checkout]\""}]

          (not (vector? parsed))
          [:err {:ok? false :reason :not-an-event-vector
                 :event event-str
                 :parsed-type (cond
                                (map? parsed)      :map
                                (keyword? parsed)  :keyword
                                (symbol? parsed)   :symbol
                                (sequential? parsed) :list
                                :else              :scalar)
                 :hint "event must be a vector, e.g. \"[:cart/checkout {:reason :user}]\""}]

          :else
          [:ok parsed])))))

(defn- runtime-envelope->result
  "Translate the runtime dispatch fn's return into the wire envelope.

  rf2-ldfnx — the runtime (`pair-dispatch!` / `pair-dispatch-sync!` /
  `dispatch-and-collect`) returns a structured envelope. On real
  success it carries `:ok? true` (sync/trace) or `:queued? true`
  (queued) plus the cascade slots. On a frame-targeting failure it
  carries `:ok? false` with a `:reason` (`:no-new-epoch`,
  `:no-epoch-recorded`, …) — the dispatch did NOT land.

  The pre-fix shape merged `{:mode <m>}` over whatever the runtime
  returned and ALWAYS emitted a success (`ok-text`) envelope. A
  frame-targeted dispatch that no-op'd (the runtime reporting
  `:ok? false`) therefore rode back as `{:mode :sync}` with no
  `:isError` flag — a silent wrong-success. The `:mode` slot is the
  caller's signal that the dispatch took effect, so it MUST appear
  only on a genuine landing.

  Contract:
    - runtime `:ok? false`  ⇒ `err-text` (`:isError true`), NO `:mode`
      slot. The failure rides through verbatim so the caller sees the
      structured `:reason`/`:hint`.
    - otherwise (success / queued / non-map degraded runtime) ⇒
      `ok-text` with `{:mode <m>}` merged in."
  [mode v]
  (if (and (map? v) (false? (:ok? v)))
    (wire/err-text v)
    (wire/ok-text (merge {:mode mode} (when (map? v) v)))))

;; ---------------------------------------------------------------------------
;; Render-settle — `:await-render` (rf2-gfu33).
;;
;; The settle form runs the dispatch synchronously (so app-db has
;; committed and the substrate has been invalidated), then returns a
;; browser-side Promise that resolves AFTER the substrate's render
;; flush AND the next paint. The flush is the framework's
;; `re-frame.interop/after-render` — a substrate-agnostic primitive
;; routed through the `:adapter/after-render` late-bind hook (Spec 006).
;; A single `requestAnimationFrame` after the flush pins resolution to
;; the paint boundary; environments without rAF (headless / SSR) resolve
;; straight off the after-render callback.
;;
;; The Promise resolves to the dispatch envelope with `:settled? true`
;; merged in, so the caller gets the full `pair-dispatch-sync!` result
;; (`:cascade-summary`, `:epoch-id`, …) AND the settle confirmation in
;; one round-trip. The server awaits the Promise via the shared
;; `await-promise` mailbox.
;; ---------------------------------------------------------------------------

(defn- await-render-callbacks
  "The dispatch `:await-render` result vocabulary for
  `await-promise/handle-sentinel`. On resolution the value IS the
  dispatch envelope (the `pair-dispatch-sync!` / `dispatch-and-collect`
  return) with `:settled? true` already merged in by the settle form —
  route it through `runtime-envelope->result` so a frame-targeting
  failure (`:ok? false`) still surfaces as an `:isError` envelope, never
  a silent `{:mode :sync}` success (the rf2-ldfnx invariant). Timeout /
  malformed-sentinel surface as structured dispatch errors."
  [mode]
  {:on-resolved (fn [{:keys [value]}] (runtime-envelope->result mode value))
   :on-rejected (fn [{:keys [rejection]}]
                  (wire/err-text {:ok?       false
                                  :reason    :rf.error/dispatch-await-render-rejected
                                  :rejection rejection}))
   :on-timeout  (fn [{:keys [timeout-ms]}]
                  (wire/err-text {:ok?        false
                                  :reason     :rf.error/dispatch-await-render-timeout
                                  :timeout-ms timeout-ms
                                  :hint       (str "the render-settle promise did not resolve within "
                                                   "timeout-ms. The dispatch may still have committed; "
                                                   "the substrate's after-render flush never fired (no "
                                                   "mounted root?) or the page reloaded mid-settle.")}))
   :on-missing  (fn [{:keys [reason sentinel]}]
                  (wire/err-text (cond-> {:ok?    false
                                          :reason :rf.error/dispatch-await-render-mailbox-missing
                                          :hint   "the render-settle mailbox vanished before the result was read."}
                                   (= :bad-sentinel reason) (assoc :sentinel sentinel))))})

(defn- render-settle-form
  "Build the CLJS source for an `:await-render` dispatch. `fn-sym` is the
  runtime dispatch fn (always a synchronous variant under await-render);
  `event-vec` + `opts-form` are the dispatch payload. Emits a form whose
  synchronous return is a `js/Promise` resolving to the dispatch envelope
  merged with `{:settled? true}` once the substrate has flushed + the
  next paint is scheduled."
  [fn-sym event-vec opts-form]
  (str
    "(js/Promise."
    "  (fn [resolve# _reject#]"
    "    (let [result# " (ef/emit (ef/rt-call fn-sym event-vec opts-form))
    "          done#   (fn [] (resolve# (if (map? result#)"
    "                                     (assoc result# :settled? true)"
    "                                     {:settled? true :result result#})))]"
    "      (re-frame.interop/after-render"
    "        (fn []"
    "          (if (exists? js/requestAnimationFrame)"
    "            (js/requestAnimationFrame (fn [_#] (done#)))"
    "            (done#)))))))"))

(defn dispatch-tool [conn args]
  (let [event-str    (wire/arg args :event)
        build-id     (wire/arg-build conn args)
        await-render? (boolean (wire/arg args :await-render))
        timeout-ms   (or (wire/arg args :timeout-ms) await-promise/default-timeout-ms)
        ;; rf2-gfu33: `:await-render` forces synchronous dispatch — the
        ;; cascade must have committed before the render can settle
        ;; against the new state. An explicit `:trace` still wins (the
        ;; caller asked for the assembled epoch); otherwise sync.
        sync?        (boolean (or (wire/arg args :sync) await-render?))
        trace?       (boolean (wire/arg args :trace))
        ;; rf2-ldfnx — coerce `frame` via the colon-tolerant
        ;; `->frame-keyword` (the shared `fresh-keyword` path), NOT the
        ;; raw `(keyword ...)` of `wire/arg-keyword`. The documented
        ;; `frame` arg is the colon-prefixed id (`":rf/xray"`, `":foo"`
        ;; — Tool-Catalogue §Id representation, rf2-cg37y). Raw
        ;; `(keyword ":rf/xray")` mints the MALFORMED `::rf/xray`
        ;; (namespace literally `":rf"`), which matches no registered
        ;; frame — the runtime then no-op'd while the tool reported
        ;; `{:mode :sync}`. `->frame-keyword` strips the leading colon
        ;; so the frame routes the same way `eval-cljs` / `snapshot` /
        ;; `reset-frame-db` already route it.
        frame        (some-> (wire/arg args :frame) args/->frame-keyword)
        fx-overrides (when-let [o (wire/arg args :fx-overrides)] (js->clj o :keywordize-keys true))
        [tag payload] (parse-event-edn event-str)]
    (case tag
      :err
      (js/Promise.resolve (wire/err-text payload))

      :ok
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            ;; rf2-vflrg: the event is now a parsed CLJS vector. Pass it
            ;; through `rt-call`'s normal arg-emit path so it is `pr-str`'d
            ;; as an EDN literal — the runtime fn receives data, not host
            ;; source. NO `rt-raw` splice on this surface.
            fn-sym     (cond trace? 'dispatch-and-collect
                             sync?  'pair-dispatch-sync!
                             :else  'pair-dispatch!)
            mode (cond trace? :trace sync? :sync :else :queued)]
        (if await-render?
          ;; rf2-gfu33 — render-settle path. The form's synchronous
          ;; return is a Promise; await it through the shared mailbox so
          ;; `dispatch → observe` is one deterministic step.
          (let [settle-form (render-settle-form fn-sym event-vec opts-form)
                mailbox-id  (await-promise/mailbox-key)
                wrapped     (await-promise/wrap-form settle-form mailbox-id)]
            (-> (probe/ensure-runtime! conn build-id)
                (.then (fn [_] (nrepl/cljs-eval-value conn build-id wrapped)))
                (.then (fn [sentinel]
                         (await-promise/handle-sentinel
                           conn build-id timeout-ms sentinel
                           (await-render-callbacks mode))))
                (.catch (fn [err] (probe/err->result :dispatch-failed err)))))
          (let [form (ef/emit (ef/rt-call fn-sym event-vec opts-form))]
            (-> (probe/ensure-runtime! conn build-id)
                (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
                (.then (fn [v] (runtime-envelope->result mode v)))
                (.catch (fn [err] (probe/err->result :dispatch-failed err))))))))))
