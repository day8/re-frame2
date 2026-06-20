(ns re-frame2-pair-mcp.tools.watch-until
  "Tool: watch-until — block until a predicate over a signal holds.

  ## Why a blocking watch primitive

  `record` is the non-blocking half of the recorder: install an observer,
  let the human interact, read back the change-log later. `watch-until` is
  the blocking half — the answer to \"wait until the focus lands on the
  modal\" / \"wait until app-db's `[:upload :status]` flips to `:done`\".
  It replaces a hand-rolled `setTimeout` poll inside `eval-cljs`, which
  carried the same timing/teardown footguns the recorder exists to kill.

  ## How it blocks

  Like `tail-build`, the server polls a cheap runtime read on a fixed
  cadence until the condition trips or `timeout-ms` elapses — no rAF loop,
  no browser-side mailbox. Each poll evals one form that (a) samples the
  signal-set via the runtime's `sample-signals` and (b) applies the
  compiled predicate to the positional sample map server-side, returning
  `{:held? bool :sample {...} :t <ms>}`. The first poll where `:held?` is
  true resolves the tool with that sample. On timeout the LAST sample
  rides back so the operator sees how close the condition got.

  ## Predicate vocabulary — DATA, not host source

  The `pred` arg is an EDN data predicate (same injection-closing posture
  as `dispatch` / `replace-app-db`). It is compiled into a pure
  value-comparison fn by `record/pred-source` — shared with `record`'s
  `:stop {:pred ...}` so the two surfaces read identically. Recognised
  shapes (matched against the positional sample map `{<signal-index>
  <value>}`):

    {:signal 0 :equals <v>}              — sample 0 equals <v>
    {:signal 0 :changed true}            — sample 0 is non-nil
    {:signal 0 :path [...] :equals <v>}  — (get-in (sample 0) path) = <v>
    {:signal 0 :contains <substr>}       — (str (sample 0)) includes <substr>
    {:signal 0}                          — sample 0 took any non-nil value

  ## Read-only by construction

  The runtime sampler only reads; `watch-until` never dispatches or
  mutates. The descriptor carries the read-only annotation."
  (:require [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.record :as record]))

(def ^:private default-timeout-ms
  "Default deadline for the predicate to hold. 30 s matches the
  recorder's default window — a generous \"interact while I watch\"
  budget. Pathologically long human-in-the-loop sessions raise it via
  the `:timeout-ms` arg."
  30000)

(def ^:private poll-ms
  "Cadence at which the server re-samples + re-tests the predicate.
  100ms = ~10 polls/sec — lands within ~100ms of the condition tripping,
  cheap on the nREPL socket. Matches `tail-build`'s probe cadence."
  100)

(defn watch-form
  "Build the per-poll eval form: sample the signal-set against the frame,
  then apply the compiled predicate to the positional sample map. Returns
  `{:held? bool :sample {...} :t <ms>}`. `pred-src` is the predicate fn
  source (from `record/pred-source`); when nil the form reports
  `:held? false` every tick (a no-predicate watch can only time out — the
  refusal is enforced at the tool boundary, so this is defensive).

  `elision-opts` (the rendered `elision-opts-edn` walker
  map) rides as the 3rd `sample-signals` arg so each sampled `:app-db` /
  `:sub` value is elided for off-box egress (the `:sample` slot is what
  the tool egresses on a hold AND the `:last-sample` on timeout). When no
  explicit `frame` is supplied the form resolves the operating frame via
  the runtime's `current-frame` so the 3-arity (which carries the elision
  opts) is always reached — the bare 1-arity would skip the redaction."
  [signals frame pred-src elision-opts]
  (let [frame-src   (if frame
                      (ef/emit frame)
                      (ef/emit (ef/rt-call 'current-frame)))
        sample-call (ef/rt-call 'sample-signals
                                signals
                                (ef/rt-raw frame-src)
                                (ef/rt-raw elision-opts))]
    (ef/emit
      (ef/rt-let
        ['r       sample-call
         'sample  (ef/rt-raw "(:sample r)")
         'held?   (ef/rt-raw (if pred-src
                               (str "(boolean ((" pred-src ") sample))")
                               "false"))]
        ;; `sample-signals` fails CLOSED with an
        ;; `:ambiguous-frame` refusal (`:ok? false`) when a frame-policy
        ;; signal can't resolve a frame under the off-box gate. Propagate
        ;; the refusal verbatim so the poll loop surfaces a clear error
        ;; instead of a misleading timeout against a nil sample.
        (ef/rt-raw "(if (false? (:ok? r)) r {:held? held? :sample sample :t (:t r)})")))))

(defn watch-until-tool
  "MCP `watch-until` handler. Polls the signal-set until the compiled
  predicate holds or `timeout-ms` elapses. See the ns docstring for the
  wire contract."
  [conn raw-args]
  (let [build-id   (wire/arg-build conn raw-args)
        frame      (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        signals    (record/parse-signals-arg (wire/arg raw-args :signals))
        pred       (let [p (wire/arg raw-args :pred)]
                     (cond
                       (map? p) p
                       (string? p) (try (let [v (cljs.reader/read-string p)]
                                          (when (map? v) v))
                                        (catch :default _ nil))
                       (object? p) (try (js->clj p :keywordize-keys true)
                                        (catch :default _ nil))
                       :else nil))
        ;; Validate `:timeout-ms` as a positive-millisecond integer up
        ;; front, the SAME contract the other timeout-aware tools use
        ;; (`tail-build :wait-ms`, `eval-cljs` / `dispatch` `:timeout-ms`
        ;; — all via `args/parse-timeout-arg`). A malformed,
        ;; zero, negative, or fractional value short-circuits to an honest
        ;; `:invalid-numeric-arg` error (the `cond` below) rather than
        ;; silently becoming `default-timeout-ms` and hiding the caller's
        ;; bad input behind a 30s wait. An ABSENT arg ⇒ `[:ok nil]` ⇒ the
        ;; documented default.
        timeout-r  (args/parse-timeout-arg "timeout-ms" (wire/arg raw-args :timeout-ms))
        timeout-ms (or (second timeout-r) default-timeout-ms)
        pred-src   (record/pred-source pred)
        ;; The `:sample` (on hold) and `:last-sample` (on
        ;; timeout) slots ship raw `:app-db` / `:sub` values back to the
        ;; model. Elide them for off-box egress under the same gate posture
        ;; as snapshot / get-path / record: gate
        ;; OFF (the published default) forces `:include-sensitive false`
        ;; + `:elision true`; gate ON honours the per-call args.
        elision?   (if (raw-state/raw-state-allowed?)
                     (args/parse-bool-arg raw-args :elision)
                     true)
        incl?      (if (raw-state/raw-state-allowed?)
                     (args/parse-bool-arg raw-args :include-sensitive)
                     false)
        elision-opts (elision/elision-opts-edn (not elision?) incl?)]
    (cond
      ;; A bad `:timeout-ms` is a caller error worth telling the agent
      ;; about, not a value to silently paper over with the default.
      ;; Short-circuit BEFORE the runtime preflight, like eval-cljs's
      ;; `:timeout-ms` validation.
      (= :err (first timeout-r))
      (js/Promise.resolve (wire/err-text (second timeout-r)))

      (or (nil? signals) (empty? signals))
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :no-signals
           :hint (str "usage: watch-until {signals '[{:app-db [:upload :status]}]' "
                      "pred {:signal 0 :equals :done} [timeout-ms 30000] [frame :rf/default]}")}))

      (nil? pred-src)
      (js/Promise.resolve
        (wire/err-text
          {:ok? false :reason :missing-pred
           :hint (str "watch-until needs a `pred` data map, e.g. {:signal 0 :equals :done} "
                      "or {:signal 0 :changed true}. Without one it can only time out.")}))

      :else
      (let [form (watch-form signals frame pred-src elision-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            ;; Flip the runtime's raw-state gate to the OFF
            ;; posture before the first poll (the raw-state tap gate)
            ;; so the sampler is fail-closed even if the eval
            ;; form's threaded opts were somehow bypassed — same prelude
            ;; step snapshot / get-path / record use.
            (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
            (.then
              (fn [_]
                (js/Promise.
                  (fn [resolve _reject]
                    (let [start  (js/Date.now)
                          latest (volatile! nil)]
                      (letfn [(poll []
                                (let [elapsed (- (js/Date.now) start)]
                                  (if (>= elapsed timeout-ms)
                                    (resolve
                                      (wire/ok-text
                                        {:ok?         false
                                         :reason      :watch-timeout
                                         :timed-out?  true
                                         :timeout-ms  timeout-ms
                                         :last-sample (:sample @latest)
                                         :hint        (str "predicate did not hold within timeout-ms. "
                                                           ":last-sample shows the final reading — compare "
                                                           "it against the predicate to see how close it got.")}))
                                    (-> (nrepl/cljs-eval-value conn build-id form)
                                        (.then
                                          (fn [resp]
                                            (vreset! latest resp)
                                            (cond
                                              ;; A fail-closed
                                              ;; refusal from `sample-signals`
                                              ;; (`:ambiguous-frame` under the
                                              ;; off-box gate) is terminal:
                                              ;; surface it as an error rather
                                              ;; than polling to a timeout.
                                              (and (map? resp) (false? (:ok? resp)))
                                              (resolve (wire/err-text resp))

                                              (and (map? resp) (:held? resp))
                                              (resolve
                                                (wire/ok-text
                                                  {:ok?       true
                                                   :held?     true
                                                   :elapsed-ms (- (js/Date.now) start)
                                                   :sample    (:sample resp)
                                                   :t         (:t resp)}))

                                              :else
                                              (js/setTimeout poll poll-ms))))
                                        (.catch
                                          (fn [_]
                                            ;; nREPL hiccup — keep polling
                                            ;; rather than collapsing the watch.
                                            (js/setTimeout poll poll-ms)))))))]
                        (poll)))))))
            (.catch (fn [err] (probe/err->result :watch-until-failed err))))))))
