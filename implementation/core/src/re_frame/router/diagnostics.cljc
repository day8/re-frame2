(ns re-frame.router.diagnostics
  "Dev-only diagnostics for the router: fallthrough-to-default warnings
  (rf2-o8m0), cross-frame dispatch-sync warnings (rf2-fp97), and the
  no-handler error path. Extracted from `re-frame.router` per rf2-0ytl4
  Phase-2 seam R-B.

  Every fn here either runs on a cold/error path or sits behind
  `interop/debug-enabled?` — production builds (`:advanced` +
  `goog.DEBUG=false`) DCE the bodies and the keyword reason-strings.

  The cross-ns indirection from the router facade is amortised: callers
  (`process-event*`, `dispatch!`, `dispatch-sync!`) all sit on the
  facade and reach into this ns only on the rare warning / error paths."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(defn non-default-frame-registered?
  "True when at least one registered, non-destroyed frame other than
  `:rf/default` exists. The `:rf.warning/dispatch-from-async-callback-
  fell-through-to-default` warning is suppressed when this is false:
  single-frame apps cannot hit the footgun (the resolution chain has
  nowhere else to land), so emitting the warning would be noise rather
  than signal. Per rf2-o8m0.

  Body gated on `interop/debug-enabled?` (the sole caller is the dev-
  only `emit-fallthrough-warning!`); production calls observe the
  trivial `false` and the `frame/frame-ids` walk + transducer DCEs."
  []
  (when interop/debug-enabled?
    (let [ids (frame/frame-ids)]
      (boolean (some (fn [k] (not= :rf/default k)) ids)))))

(defn emit-fallthrough-warning!
  "Per rf2-o8m0: dispatch landed on `:rf/default` purely because the
  resolution chain found nothing else (dynamic var unbound, adapter
  React-context unresolvable, no explicit `:frame` opt) AND the target
  handler does not exist on `:rf/default`. The user almost certainly
  wanted the dispatch to ride a non-default frame; the most common
  trigger is dispatching from an async callback (setTimeout,
  addEventListener, requestAnimationFrame, Promise.then) attached
  inside a view body, where the surrounding `*current-frame*` binding
  does not survive the async escape (per Spec 002 §Dispatches issued
  from inside a handler body).

  Suppressed when no non-default frame is registered — single-frame
  apps cannot hit the footgun.

  `:source-coord` is left to the existing `:rf.trace/trigger-handler`
  surface (rf2-3nn8): when a handler is in scope, `emit-error!` /
  `emit!` hoists the triggering handler's source-coord automatically.
  When no handler is in scope (the async-callback case the warning is
  primarily aimed at) `*handler-scope*`'s `:trigger-handler` is nil and the
  field is omitted — `dispatch` is a fn, not a macro, so the call site
  cannot be stamped without changing the public API. Documented
  limitation; tools that need call-site attribution capture it
  externally.

  Body gated on `interop/debug-enabled?` so the warning surface DCEs
  wholesale under `:advanced` + `goog.DEBUG=false` (rf2-gaqwr): the
  `:fell-through-to-default?` envelope key is only set in dev (per
  `build-envelope`) so the inner `(when ...)` is always falsy in
  production, but without the outer compile-time gate the reason-string
  allocation, the warning keyword's interned slot, the
  `non-default-frame-registered?` call, and the helper-fn closure all
  survive Closure DCE."
  [envelope]
  (when interop/debug-enabled?
    (when (and (:fell-through-to-default? envelope)
               (non-default-frame-registered?))
      (let [event    (:event envelope)
            event-id (first event)
            reason   (str "Dispatch of `" event-id "` resolved to `:rf/default` "
                          "because no `:frame` was supplied and `*current-frame*` "
                          "was unbound, but no handler for that event is "
                          "registered on `:rf/default`. The dispatch most "
                          "likely originated from an async callback "
                          "(`setTimeout`, `addEventListener`, "
                          "`requestAnimationFrame`, `Promise.then`) attached "
                          "from inside a view body — the surrounding "
                          "frame-context binding does not survive the async "
                          "escape (per Spec 002 §Dispatches issued from "
                          "inside a handler body). Fixes (priority order): "
                          "(a) use `:dispatch-later` or a registered `reg-fx` "
                          "— both capture the frame in their closure; "
                          "(b) capture `(rf/frame-handle)` inside the "
                          "render and call its `:dispatch` op from the callback; "
                          "(c) attach the listener from a Form-3 "
                          "`:component-did-mount` / `use-effect` hook so "
                          "the handle is captured during render but "
                          "the listener runs after commit.")]
        (trace/emit! :warning
                     :rf.warning/dispatch-from-async-callback-fell-through-to-default
                     {:event        event
                      :event-id     event-id
                      :detected-at  (interop/now-ms)
                      :routed-to    :rf/default
                      :reason       reason
                      :recovery     :no-recovery})))))

(defn handle-no-handler!
  "Per rf2-o8m0: when a dispatch lands on `:rf/default` purely because
  resolution fell through (no `:frame` opt, dynamic var unbound, adapter
  context unresolvable) AND the handler is missing from `:rf/default`,
  the user-supplied event almost certainly belongs to a different frame
  and the dispatch lost its frame-context binding mid-flight (typically:
  an async callback attached inside a view body). Emit the warning ahead
  of the `:rf.error/no-such-handler` error so consumers see the specific
  diagnostic; the error fires too, preserving the existing handler-
  missing trace contract."
  [envelope event-id event frame]
  (emit-fallthrough-warning! envelope)
  (trace/emit-error! :rf.error/no-such-handler
                     {:rf.trace/event-id event-id
                      :rf.event/v        event
                      :frame             frame
                      :kind              :event
                      :recovery          :replaced-with-default}))

(defn other-frame-mid-drain
  "Per rf2-fp97 — Spec 002 §dispatch-sync cross-frame note. Return the
  frame-id of any registered, non-destroyed frame OTHER than `target-id`
  whose router currently shows `:in-sync-drain?` or `:in-drain?` true.
  Returns nil when no such frame exists.

  Used by `re-frame.router/dispatch-sync!` to detect the cross-frame
  cascade pattern (frame A mid-drain, a handler calls
  `(rf/dispatch-sync! [...] {:frame :b})`). The same-frame case is
  already an error; the cross-frame case is intentional but surprising,
  so we surface it as
  `:rf.warning/cross-frame-dispatch-sync-during-drain` rather than
  refuse. Frames are independent state machines (per Spec 002 §Rules
  rule 1) and frame B's drain doesn't violate frame A's contract.

  Dev-only — the caller gates on `interop/debug-enabled?` to skip the
  registry walk in production."
  [target-id]
  (some (fn [id]
          (when (not= id target-id)
            (when-let [fr (frame/frame id)]
              (let [router-state @(:router fr)]
                (when (or (:in-sync-drain? router-state) (:in-drain? router-state))
                  id)))))
        (frame/frame-ids)))

(def ^:const known-dispatch-opts
  "Per rf2-jbzhj — the closed set of keys `build-envelope` reads off the
  `dispatch` / `dispatch-sync` opts map (and therefore the only keys that
  affect dispatch behaviour). Every other key is silently swallowed, so a
  typo'd opt (`:fram` for `:frame`, `:src` for `:source`) changes nothing
  and gives no signal — the no-silent-swallow principle (rf2-3nbl5.1)
  forbids that quietness. `emit-unknown-dispatch-opts-warning!` warns on
  any opts key outside this set.

  The set mirrors the reads in `re-frame.router/build-envelope`:
    :frame                  resolved target frame
    :fx-overrides           per-call fx-id remapping
    :interceptor-overrides  per-call interceptor remapping
    :trace-id               tooling correlation id
    :source                 closed-enum trigger-kind / functional-origin
    :source-detail          per-source-kind detail payload
    :origin                 actor identity tag
    :rf.trace/call-site     macro-stamped invocation coord (dev-only)
    :rf.machine/internal?   machine-internal continuation flag (front-queue)"
  #{:frame :fx-overrides :interceptor-overrides :trace-id :source
    :source-detail :origin :rf.trace/call-site :rf.machine/internal?})

(defn unknown-dispatch-opts
  "Return the seq of keys in `opts` that fall OUTSIDE
  `known-dispatch-opts`, or nil when every key is known (so callers can
  `when-let` the result). Dev-only — the sole caller gates on
  `interop/debug-enabled?`."
  [opts]
  (when interop/debug-enabled?
    (seq (remove known-dispatch-opts (keys opts)))))

(defn emit-unknown-dispatch-opts-warning!
  "Per rf2-jbzhj: emit `:rf.warning/unknown-dispatch-opt` when a
  `dispatch` / `dispatch-sync` opts map carries one or more keys outside
  the recognised `known-dispatch-opts` set. The runtime reads only the
  known keys in `build-envelope`; an unrecognised key (almost always a
  typo — `:fram` instead of `:frame`) is otherwise silently swallowed and
  changes nothing, producing wrong behaviour with no signal. Pre-alpha
  posture: surface it loudly rather than ship a quiet footgun (aligns with
  the committed no-silent-swallow principle, rf2-3nbl5.1).

  One warning per dispatch call carrying unknown keys: the message names
  every bad key and the full known set so the fix is obvious. The dispatch
  proceeds unchanged — this is observational, never refusal (`:recovery
  :no-recovery`).

  Body gated on `interop/debug-enabled?` so the whole surface — the
  warning keyword's interned slot, the reason-string allocation, the
  `unknown-dispatch-opts` walk — DCEs wholesale under `:advanced` +
  `goog.DEBUG=false` (rf2-gaqwr). The caller in `build-envelope` reads the
  unknown-key seq inside the same gate so production never walks the opts."
  [unknown event]
  (when interop/debug-enabled?
    (let [event-id (first event)
          unknown  (vec unknown)
          reason   (str "Dispatch of `" event-id "` was given unrecognised "
                        "opts key" (when (> (count unknown) 1) "s") " "
                        (pr-str unknown) ". The runtime reads only "
                        (pr-str (vec (sort known-dispatch-opts)))
                        " — any other key is silently ignored, so a typo "
                        "(e.g. `:fram` for `:frame`) changes nothing and "
                        "gives no signal. Check for a misspelt opt; if you "
                        "intended a custom payload, put it inside the event "
                        "vector, not the dispatch opts map.")]
      (trace/emit! :warning
                   :rf.warning/unknown-dispatch-opt
                   {:event        event
                    :event-id     event-id
                    :unknown-keys unknown
                    :known-keys   (vec (sort known-dispatch-opts))
                    :detected-at  (interop/now-ms)
                    :reason       reason
                    :recovery     :no-recovery}))))

(defn emit-cross-frame-warning!
  "Per rf2-fp97: emit `:rf.warning/cross-frame-dispatch-sync-during-drain`
  when `dispatch-sync!` lands on frame `target-id` while a different
  frame (`other-id`) is mid-drain. The caller frame is read from
  `frame/*current-frame*`; when unbound (no frame context — e.g. a
  process-level REPL caller threading the dispatch through some unusual
  path) the field is `:rf/none`.

  Per Mike's 2026-05-13 Option B decision: warn, do not refuse.
  Continues with the dispatch."
  [target-id other-id event]
  (let [caller-id (or frame/*current-frame* :rf/none)
        reason    (str "dispatch-sync! against `" target-id "` while frame `"
                       other-id "` is mid-drain. The two cascades will "
                       "interleave: `" target-id "`'s drain runs to settled "
                       "while `" other-id "` is still in flight, then `"
                       other-id "` continues. Frames are independent state "
                       "machines so this does not violate either frame's "
                       "contract (per Spec 002 §Run-to-completion §Rules "
                       "rule 1 — no cross-frame drain), but the interleaved "
                       "ordering is rarely the caller's intent. If the goal "
                       "is fire-and-forget cross-frame coordination, prefer "
                       "the async form `(rf/dispatch event {:frame other})` "
                       "— it queues on the target frame's router and drains "
                       "on a later cycle, after the caller's cascade settles.")]
    (trace/emit! :warning
                 :rf.warning/cross-frame-dispatch-sync-during-drain
                 {:caller-frame caller-id
                  :target-frame target-id
                  :other-frame  other-id
                  :event        event
                  :reason       reason
                  :recovery     :no-recovery})))
