(ns re-frame2-pair-mcp.tools.describe-image
  "Tool: describe-image — describe the IMAGE GENERATION a frame is running
  (EP-0023 Use-Case 7 + Ref-Plan item 17).

  ## What this answers

  \"What behaviour does THIS frame run, and where did each piece come
  from?\" — the EP-0023 forward read over a frame's resolved image
  generation. A frame runs a composed IMAGE (selected registrations from
  one-or-more namespaces / inline sections, plus framework standards); the
  SAME `(kind, id)` can resolve DIFFERENTLY per frame, so the SELECTED
  universe a frame actually runs is what an agent driving it should see —
  not the realm-wide registrar union.

  Routes through the runtime preload's `describe-image`, which reads ONLY
  the PUBLIC facade `rf/frame-generation` read.
  EP-0023 forbids tools from consuming `re-frame.live-frame` /
  `re-frame.image-assembly` internals directly; the facade re-surfaces the
  behaviour through the public read.

  ## Args (all optional)

    :frame        keyword / string — the frame to describe. Defaults to the
                  operating frame; a multi-frame session with no selection
                  returns `{:ok? false :reason :ambiguous-frame}` rather
                  than silently reading `:rf/default`.
    :include-ns   boolean (default false) — when true, the result carries
                  `:registrations {[kind id] coordinate …}` — every selected
                  `(kind, id)` with its provenance/standard coordinate
                  (which source won the resolution). Default OFF: the full
                  resolver can be large, and `:counts` + the per-kind
                  `list-handlers {:frame …}` drill cover the common case.

  ## Returns

    `{:ok? true :frame <id>
      :images   [<image-id> …]        the composed image ids
      :kinds    [<kind> …]            registrar kinds present (sorted)
      :counts   {<kind> N …}          selected-registration count per kind
      :registrations {[kind id] coordinate …}}   ; only with :include-ns true

  (EP-0026, rf2-dlvmpc: image-declared host capabilities are removed
  end-to-end — there is no `:rf.gen/requires`, so this tool no longer reports
  a `:requires` capability set.)"
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn describe-image-tool [conn raw-args]
  (let [build-id    (wire/arg-build conn raw-args)
        frame       (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        include-ns? (args/parse-bool-arg raw-args :include-ns)
        opts        (cond-> {}
                      frame       (assoc :frame frame)
                      include-ns? (assoc :include-ns? true))
        form        (ef/emit (ef/rt-call 'describe-image opts))]
    (probe/eval-after-runtime!
      conn build-id form :describe-image-failed
      (fn [v]
        (if (map? v)
          (wire/ok-text v)
          (wire/err-text {:ok? false :reason :unexpected-shape :value v}))))))
