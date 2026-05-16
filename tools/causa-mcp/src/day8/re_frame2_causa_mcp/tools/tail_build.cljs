(ns day8.re-frame2-causa-mcp.tools.tail-build
  "Tool: `tail-build` — wait for hot-reload to land (rf2-8xzoe.31,
  T-Meta-2 of the causa-mcp meta tranche).

  Two modes:

  - **Probe mode** (default when `:probe` supplied) — poll the
    runtime's `tail-build-probe` accessor (a monotonic counter that
    increments every call and survives `:after-load` but resets on
    full page refresh). The user's `:probe` form is evaluated; if its
    value differs from the value captured at start, the hot-reload
    landed and the tool resolves `:ok? true`. If `:wait-ms` elapses
    without change, the tool resolves `:ok? false` with
    `:reason :timed-out` (likely a compile error — the operator can
    look at the shadow-cljs build log).

  - **Soft-delay mode** (no `:probe`) — resolves after a fixed
    300ms delay (`no-probe-soft-delay-ms`). Mirrors pair2-mcp's
    behaviour: the bundle-swap cycle empirically lands within ~300ms
    of a source-file save; agents that just need 'a bit of breathing
    room' get the same coarse delay without instrumenting a probe.

  ## Why this lives MCP-server-side, not runtime-side

  The runtime's `tail-build-probe` accessor returns the monotonic
  counter; the change-detect logic (capture before, poll after,
  compare) lives here per the runtime ns docstring's split. The
  runtime's job is to expose a value that's stable across calls but
  different after a real hot-reload; this tool's job is to wait for
  the difference.

  ## Wire-boundary contract

  - **B-1 privacy** — not applicable (build-status metadata).
  - **W-6 size elision** — not applicable (envelope is tiny scalars).
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:probe` | string | nil | CLJS source whose value-change signals reload |
  | `:wait-ms` | int | 5000 | timeout for probe-mode |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      ;; probe mode — change detected
      {:ok? true :t <ms> :soft? false}

      ;; probe mode — timeout
      {:ok? false :reason :timed-out :timed-out? true
       :note \"Probe did not change within wait-ms. Likely a compile error.\"}

      ;; soft-delay mode
      {:ok? true :t <ms> :soft? true :note <delay-explanation>}

      ;; probe eval threw
      {:ok? false :reason :probe-failed :message <s>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #31. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:private default-wait-ms
  "Default deadline for the probe to change after a hot-reload. Five
  seconds is generous for a typical shadow-cljs incremental rebuild;
  heavy ns reloads / first-time-load scenarios can override via the
  `:wait-ms` MCP arg."
  5000)

(def ^:private probe-poll-ms
  "Cadence at which we re-evaluate the probe form when waiting for
  its value to change. 100ms = ~10 probes/sec — fine-grained enough
  to land within ~100ms of the reload completing, cheap enough on
  the nREPL socket to not flood it."
  100)

(def ^:private no-probe-soft-delay-ms
  "When the caller passes no probe form, we resolve after a fixed
  soft delay — mirrors pair2-mcp's behaviour. 300ms is the span
  empirical observation places shadow-cljs's bundle-swap cycle
  within after the source-file save event fires."
  300)

(defn tail-build-tool [conn args]
  (let [build-id (wire/arg-build args)
        wait-ms  (or (wire/arg-int args :wait-ms default-wait-ms)
                     default-wait-ms)
        probe    (wire/arg args :probe)
        poll-ms  probe-poll-ms]
    (cond
      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (wire/ok-text
                         {:ok?   true
                          :t     (js/Date.now)
                          :soft? true
                          :note  (str "No probe supplied; waited a "
                                      no-probe-soft-delay-ms
                                      "ms fixed delay.")})))
            no-probe-soft-delay-ms)))

      :else
      (let [start (js/Date.now)]
        (-> (nrepl/cljs-eval-value conn build-id probe)
            (.then
              (fn [before]
                (js/Promise.
                  (fn [resolve _]
                    (letfn [(poll []
                              (js/setTimeout
                                (fn []
                                  (let [elapsed (- (js/Date.now) start)]
                                    (if (>= elapsed wait-ms)
                                      (resolve
                                        (wire/ok-text
                                          {:ok?        false
                                           :reason     :timed-out
                                           :timed-out? true
                                           :note       (str "Probe did not change within "
                                                            wait-ms
                                                            "ms. Likely a compile error.")}))
                                      (-> (nrepl/cljs-eval-value conn build-id probe)
                                          (.then
                                            (fn [now]
                                              (if (not= now before)
                                                (resolve (wire/ok-text
                                                           {:ok?   true
                                                            :t     (js/Date.now)
                                                            :soft? false}))
                                                (poll))))
                                          (.catch (fn [_] (poll)))))))
                                poll-ms))]
                      (poll))))))
            (.catch
              (fn [err]
                (wire/ok-text {:ok?     false
                               :reason  :probe-failed
                               :message (.-message err)}))))))))

(def descriptor
  {:name        "tail-build"
   :description (str "Wait for hot-reload to land. Probe mode (when "
                     ":probe is supplied): poll the form's value, "
                     "resolve when it changes (signaling the reload "
                     "completed) or :reason :timed-out after :wait-ms "
                     "(default 5000). Soft-delay mode (no :probe): "
                     "resolve after a fixed 300ms delay — gives "
                     "shadow-cljs's bundle-swap cycle a chance to "
                     "complete without instrumenting a probe.")
   :input-schema #js {:type "object"
                      :properties #js {:probe      #js {:type "string"}
                                       :wait-ms    #js {:type "integer"}
                                       :max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) tail-build-tool descriptor)
