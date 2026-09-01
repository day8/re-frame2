(ns re-frame2-pair-mcp.tools.tail-build
  "Tool: tail-build — wait for hot-reload to land.

  ## Pre-edit baseline (rf2-1f60u)

  The comparison value is supplied by the CALLER, captured BEFORE the
  source edit: evaluate the probe form once (eval-cljs), keep its printed
  value, edit, then call tail-build with the same `:probe` plus that
  `:baseline`. Success is the first sample whose value differs from the
  baseline — including the very first one. That makes a successful reload
  recognizable whether it lands before or after this call obtains its
  first sample:

    - FAST reload (landed before the first probe): the first sample is
      already the new value ≠ baseline → immediate success.
    - SLOW reload: samples equal the baseline until a later poll differs
      → success then.

  The previous contract self-baselined on the first post-call sample,
  so a reload that landed in the gap between the file write and the
  first nREPL evaluation made every sample \"the new value\" and the tool
  returned `:timed-out` on a SUCCESSFUL reload. A post-edit self-baseline
  cannot distinguish 'already reloaded' from 'never changed', which is
  why `:baseline` is REQUIRED whenever `:probe` is supplied (`:reason
  :missing-baseline` otherwise — pre-alpha, no legacy self-baseline mode).

  Baseline matching uses the sample's printed form: a sample matches when
  its `pr-str` OR its `str` rendering equals the supplied string — the
  forgiving second rendering means a string-valued probe captured without
  its quotes still compares as intended rather than instantly
  false-succeeding.

  ## Probe-value diagnostics

  When a probe form is supplied, both the success and timeout responses
  carry `:probe-values {:baseline <b> :initial <v0> :final <v-last>}` —
  the caller's pre-edit evidence and the two ends of what the polling
  loop observed. On timeout the operator can distinguish three failure
  modes from one envelope:

    1. every sample equal to the baseline → either the hot-reload
       genuinely didn't land (compile error / wrong build) or the probe
       form cannot discriminate this edit (same value before and after —
       choose a source-derived fingerprint that changes, e.g. a
       handler-meta hash or `:line`).
    2. `:initial` nil and `:final` nil → the probe form returned nil on
       every iteration; possibly the cljs-eval routed to a runtime that
       hasn't loaded the target ns, or the form is bound to a missing var.
    3. probe raised on the initial evaluation → `:probe-errored` envelope
       with `:probe-error <stringified ex>`.

  Returning the full comparison lets the operator distinguish a
  non-discriminating probe from a genuinely stalled rebuild directly from
  the envelope, instead of manually calling `handler-meta` to confirm the
  rebuild landed."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(def ^:private default-wait-ms
  "Default deadline for the probe to leave the baseline after a
  hot-reload. Five seconds is generous for a typical shadow-cljs
  incremental rebuild; heavy ns reloads / first-time-load scenarios can
  override via the `:wait-ms` MCP arg."
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
  (str "Probe value never left the supplied baseline within wait-ms. "
       "Possible causes: (a) a compile error in shadow stalled the rebuild "
       "(confirm from shadow/browser output, not from this timeout alone), "
       "(b) the probe form cannot discriminate this edit — its value is the "
       "same before and after the reload; choose a source-derived "
       "fingerprint that changes (e.g. a handler-meta hash or :line), "
       "(c) the probe form errored on some samples — "
       "check :probe-values to disambiguate."))

(def ^:private probe-errored-note
  "Hint surfaced on the probe-errored envelope — the probe form raised
  on the initial evaluation, so no before/after comparison ever ran.
  Almost always a malformed probe form (typo, dotted-form host interop
  against a missing var, etc.)."
  "Probe form raised an exception on its initial evaluation. The form is likely malformed.")

(def ^:private missing-baseline-note
  "Hint surfaced when `:probe` arrives without `:baseline`."
  (str "tail-build requires :baseline whenever :probe is supplied. Evaluate "
       "the SAME probe form (eval-cljs) BEFORE making the source edit, keep "
       "its printed :value verbatim, edit, then pass it here. The pre-edit "
       "baseline is what makes a reload recognizable even when it lands "
       "before this call's first sample — a post-edit self-baseline cannot "
       "distinguish 'already reloaded' from 'never changed'."))

(def ^:private baseline-without-probe-note
  "Hint surfaced when `:baseline` arrives without `:probe`."
  (str ":baseline without :probe has nothing to compare — supply the probe "
       "form the baseline was captured from."))

(defn- matches-baseline?
  "True when the sampled value still reads as the caller's pre-edit
  baseline. Both printed renderings are accepted: `pr-str` (the canonical
  capture — what eval-cljs's envelope shows) and `str` (forgives a
  string-valued probe captured without its quotes, which would otherwise
  false-succeed on the very first sample)."
  [baseline v]
  (or (= baseline (pr-str v))
      (= baseline (str v))))

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
        baseline (wire/arg args :baseline)
        ;; A JSON-numeric baseline (42) is normalised to its printed form —
        ;; identical to the pr-str of the number it names.
        baseline (when (some? baseline) (str baseline))
        poll-ms  probe-poll-ms]
    (cond
      (= :err (first wait-r))
      (js/Promise.resolve (wire/err-text (second wait-r)))

      (and (nil? probe) (some? baseline))
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :baseline-without-probe
                        :note   baseline-without-probe-note}))

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

      ;; A probe without its pre-edit baseline re-creates the fast-reload
      ;; race this contract exists to close — refuse it with the capture
      ;; recipe rather than self-baselining (rf2-1f60u).
      (nil? baseline)
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :missing-baseline
                        :note   missing-baseline-note}))

      :else
      (let [start (js/Date.now)]
        (-> (nrepl/cljs-eval-value conn build-id probe)
            (.then
              (fn [first-sample]
                (if-not (matches-baseline? baseline first-sample)
                  ;; The reload landed BEFORE our first sample — the fast
                  ;; ordering. The pre-edit baseline is what makes this
                  ;; recognizable as success rather than a stuck value.
                  (js/Promise.resolve
                    (wire/ok-text {:ok?           true
                                   :t             (js/Date.now)
                                   :soft?         false
                                   :probe-values  {:baseline baseline
                                                   :initial  first-sample
                                                   :final    first-sample}}))
                  (js/Promise.
                    (fn [resolve _]
                      ;; `latest` carries the most-recent probe value we
                      ;; saw — initialised to the first sample and
                      ;; overwritten on every successful poll. On timeout
                      ;; we hand the whole comparison back to the operator
                      ;; under :probe-values.
                      (let [latest (volatile! first-sample)]
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
                                               :probe-values  {:baseline baseline
                                                               :initial  first-sample
                                                               :final    @latest}
                                               :note          timeout-note}))
                                          (-> (nrepl/cljs-eval-value conn build-id probe)
                                              (.then
                                                (fn [now]
                                                  (vreset! latest now)
                                                  (if-not (matches-baseline? baseline now)
                                                    (resolve
                                                      (wire/ok-text
                                                        {:ok?           true
                                                         :t             (js/Date.now)
                                                         :soft?         false
                                                         :probe-values  {:baseline baseline
                                                                         :initial  first-sample
                                                                         :final    now}}))
                                                    (poll))))
                                              (.catch (fn [_] (poll)))))))
                                    poll-ms))]
                          (poll))))))))
            (.catch
              (fn [err]
                ;; Initial probe-eval threw — surface as :probe-errored
                ;; (distinct from :timed-out, which means "polled but
                ;; never left the baseline"). With probe-error included
                ;; the operator gets the underlying exception verbatim.
                ;; rf2-acckgr: `:ok? false` MUST ride as err-text per
                ;; spec/003's universal isError rule.
                (wire/err-text {:ok?         false
                                :reason      :probe-errored
                                :probe-error (.-message err)
                                :note        probe-errored-note}))))))))
