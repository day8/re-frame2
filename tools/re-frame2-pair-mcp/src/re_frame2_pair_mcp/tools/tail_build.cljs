(ns re-frame2-pair-mcp.tools.tail-build
  "Tool: tail-build — wait for hot-reload to land.

  ## Probe-value diagnostics

  When a probe form is supplied, both the success and timeout responses
  carry `:probe-values {:initial <v0> :final <v-last>}` — the two ends
  of the comparison the polling loop is making. On timeout the operator
  can therefore distinguish three failure modes from one envelope:

    1. `:initial` and `:final` equal → either the probe form never
       changes (the comparison cannot drive completion) or the
       hot-reload genuinely didn't land (compile error / wrong build).
    2. `:initial` nil and `:final` nil → the probe form returned nil
       on every iteration; possibly the cljs-eval routed to a runtime
       that hasn't loaded the target ns, or the form is bound to a
       missing var.
    3. `:initial` present and `:final` errored consistently →
       `:probe-errored` envelope with `:probe-error <stringified ex>`.

  Returning both ends of the comparison lets the operator distinguish a
  malformed probe form (which can never change) from a genuinely stalled
  rebuild directly from the envelope, instead of manually calling
  `handler-meta` to confirm the rebuild landed."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.wire :as wire]))

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
  soft delay — matches the bash-shim's behaviour. 300ms is the
  span empirical observation places shadow-cljs's bundle-swap cycle
  within after the source-file save event fires."
  300)

(def ^:private timeout-note
  "Hint surfaced on the timeout envelope. The probe-values ride back
  alongside it, so the operator can read the values themselves to pick
  the actual cause from the listed candidates."
  (str "Probe value did not change within wait-ms. "
       "Possible causes: (a) compile error in shadow stalled the rebuild, "
       "(b) probe form returns the same value before and after the reload, "
       "(c) probe form errored — check :probe-values to disambiguate."))

(def ^:private probe-errored-note
  "Hint surfaced on the probe-errored envelope — the probe form raised
  on EVERY iteration (initial fetch + every poll), so we can't even
  measure a before/after delta. Almost always a malformed probe form
  (typo, dotted-form host interop against a missing var, etc.)."
  "Probe form raised an exception on every iteration. The form is likely malformed.")

(defn tail-build-tool [conn args]
  (let [build-id (wire/arg-build conn args)
        ;; Validate `:wait-ms` as a positive-millisecond
        ;; integer BEFORE it reaches the `(>= elapsed wait-ms)` poll
        ;; comparison. A non-numeric value (`"never"`) makes `(>= n NaN)`
        ;; never true ⇒ the probe-change loop polls the nREPL socket
        ;; FOREVER; a zero / negative value times out immediately. Absent
        ;; ⇒ the documented `default-wait-ms`.
        wait-r   (args/parse-timeout-arg "wait-ms" (wire/arg args :wait-ms))
        wait-ms  (or (second wait-r) default-wait-ms)
        probe    (wire/arg args :probe)
        poll-ms  probe-poll-ms]
    (cond
      (= :err (first wait-r))
      (js/Promise.resolve (wire/err-text (second wait-r)))

      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (wire/ok-text {:ok?   true
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
                    ;; `latest` carries the most-recent probe value we
                    ;; saw — initialised to `before` and overwritten on
                    ;; every successful poll. On timeout we hand both
                    ;; ends back to the operator under :probe-values.
                    (let [latest (volatile! before)]
                      (letfn [(poll []
                                (js/setTimeout
                                  (fn []
                                    (let [elapsed (- (js/Date.now) start)]
                                      (if (>= elapsed wait-ms)
                                        ;; rf2-acckgr: a timed-out probe
                                        ;; wait is `:ok? false` — a
                                        ;; known-tool failure per
                                        ;; spec/003's universal isError
                                        ;; rule, not a success carrying
                                        ;; bad news.
                                        (resolve
                                          (wire/err-text
                                            {:ok?           false
                                             :reason        :timed-out
                                             :timed-out?    true
                                             :probe-values  {:initial before
                                                             :final   @latest}
                                             :note          timeout-note}))
                                        (-> (nrepl/cljs-eval-value conn build-id probe)
                                            (.then
                                              (fn [now]
                                                (vreset! latest now)
                                                (if (not= now before)
                                                  (resolve
                                                    (wire/ok-text
                                                      {:ok?           true
                                                       :t             (js/Date.now)
                                                       :soft?         false
                                                       :probe-values  {:initial before
                                                                       :final   now}}))
                                                  (poll))))
                                            (.catch (fn [_] (poll)))))))
                                  poll-ms))]
                        (poll)))))))
            (.catch
              (fn [err]
                ;; Initial probe-eval threw — surface as :probe-errored
                ;; (distinct from :timed-out, which means "polled but
                ;; never changed"). With probe-error included the
                ;; operator gets the underlying exception verbatim.
                ;; rf2-acckgr: `:ok? false` MUST ride as err-text per
                ;; spec/003's universal isError rule.
                (wire/err-text {:ok?         false
                                :reason      :probe-errored
                                :probe-error (.-message err)
                                :note        probe-errored-note}))))))))
