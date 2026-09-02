(ns re-frame2-pair-mcp.tools.replay-epoch
  "Tool: replay-epoch — strict replay of a retained epoch, in ONE call.

  Per spec/Tool-Pair.md §Replay (rf2-ov144): re-drive the named epoch's
  recorded event through the app's own handlers with the RAW
  argument-bearing `:trigger-event`, the recorded post-generation
  `:rf.cofx` token under `:rf.cofx/mint-policy :strict`, and the record's
  own `:fx-overrides` / `:interceptor-overrides` — all resolved
  IN-PROCESS by the preload runtime's `replay-epoch` primitive
  (`(rf/replay-epoch! frame-id epoch-id {:origin :pair})`). Nothing
  crosses the wire but the id: the caller never reads, copies or
  re-supplies the event / cofx / override payloads that the projected
  epoch pages deliberately expose only with `:rf/redacted` args
  (rf2-nm611o). This is what the `dispatch {replay true …}` recipe could
  not offer an off-box agent.

  ## Gate — dispatch authority, NOT the writes gate

  Like `dispatch`, this tool drives the application's own handlers; it
  rewrites no partition out of band. So it takes `dispatch`'s posture
  (default-ON, `:origin :pair`, destructive annotations) and is NOT
  behind `--allow-writes` — that gate names the two out-of-band
  state-rewrite tools (`restore-epoch` / `replace-app-db`) and stays
  exactly that.

  ## Sensitive-read posture

  The success envelope carries a `:cascade-summary` whose
  `:event-vector` copies the NEW epoch's raw `:trigger-event`. Exactly
  like `dispatch` / `restore-epoch`, this tool issues
  `raw-state/signal-runtime!` between `ensure-runtime!` and the eval so
  the runtime's `configure-raw-state!` posture is the server's boot-gate
  state BEFORE the summary is built — under the default gate-OFF
  posture the args redact to `:rf/redacted`.

  ## epoch-id is `:any`

  Parsed as EDN, not assumed `string?` — the reference epoch runtime
  emits INTEGER epoch-ids (the same `:any` contract `restore-epoch` and
  the cursor use). `\"7\"` reads as the integer 7; an unreadable value
  returns `:invalid-epoch-id`.

  ## Result

  The runtime returns a structured envelope in every case (see its
  docstring): the `dispatch`-shaped consequence + `:replayed? true` +
  `:source-epoch-id` on success; a `{:ok? false :reason …}` refusal
  decided BEFORE anything dispatched (unknown / aged-out id, drain in
  flight, halted / synthetic / incomplete record, recorded
  `:rf/fn-override`, unknown frame); or the canonical
  `:rf.error/missing-required-cofx` when the strict re-dispatch failed
  loud. Every `:ok? false` rides as `isError: true` (003-Tool-Catalogue
  §\"Every :ok? false response is isError: true\")."
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(def ^:private usage-hint
  "usage: replay-epoch {epoch-id '<id>' [frame :foo]}. The id is parsed as EDN — an integer id like 7 may be passed as \"7\". Find ids with trace-window / watch-epochs / snapshot (:epochs slice).")

(defn replay-epoch-tool [conn raw-args]
  (let [epoch-id-str  (wire/arg raw-args :epoch-id)
        build-id      (wire/arg-build conn raw-args)
        frame         (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        [tag payload] (args/read-edn-arg epoch-id-str :missing-epoch-id :invalid-epoch-id)]
    (case tag
      :err
      (js/Promise.resolve
        (wire/err-text
          {:ok?      false
           :reason   payload
           :epoch-id epoch-id-str
           :hint     usage-hint}))

      :ok
      (let [epoch-id payload
            ;; replay-epoch's runtime arglist is ([epoch-id]
            ;; [epoch-id frame-id]) — the frame is the SECOND arg, exactly
            ;; like restore-epoch.
            call (if frame
                   (ef/rt-call 'replay-epoch epoch-id frame)
                   (ef/rt-call 'replay-epoch epoch-id))
            form (ef/emit call)
            on-value
            (fn [v]
              ;; The runtime returns a structured envelope in every
              ;; case. A non-map value can only come from an out-of-date
              ;; preload that predates `replay-epoch`; surface that
              ;; honestly rather than as a success.
              (let [result (if (map? v)
                             v
                             {:ok?      false
                              :reason   :replay-unavailable
                              :epoch-id epoch-id
                              :frame    frame
                              :hint     (str "the preload runtime returned a non-envelope value "
                                             "(" (pr-str v) ") — the shipped re-frame2-pair.runtime "
                                             "predates replay-epoch; reload the app with the current "
                                             "preload.")})]
                ;; A refusal is NOT a terminal-empty outcome: nothing was
                ;; dispatched (or the strict dispatch failed loud). It MUST
                ;; ride as `isError: true` so the host never reads a
                ;; success-shaped envelope over an unchanged frame.
                (if (false? (:ok? result))
                  (wire/err-text result)
                  (wire/ok-text result))))]
        ;; The signalled prelude pushes the raw-state posture to the
        ;; runtime BEFORE the replay eval, so the NEW epoch's
        ;; cascade-summary `:event-vector` redacts under the default
        ;; gate-OFF posture — the dispatch / restore-epoch prelude shape.
        (probe/eval-after-runtime-signalled!
          conn build-id form :replay-epoch-failed on-value)))))
