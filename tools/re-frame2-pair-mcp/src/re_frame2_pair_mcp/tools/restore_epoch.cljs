(ns re-frame2-pair-mcp.tools.restore-epoch
  "Tool: restore-epoch — time-travel undo (rf2-ee38b.18).

  The canonical pair-tool undo gesture per spec/Tool-Pair.md
  §Time-travel: rewind a frame's `app-db` to a recorded prior epoch.
  Wraps the preload runtime's `restore-epoch` primitive
  (`(rf/restore-epoch frame-id epoch-id)`), which is itself the
  Tool-Pair `restore-epoch` write primitive the server is the canonical
  consumer of (000-Vision / 003-Tool-Catalogue).

  ## Gate (rf2-ee38b.18)

  A write surface — gated behind `--allow-writes` (default OFF). When
  the gate is closed the tool returns `:rf.error/writes-disabled`
  without touching the nREPL socket. See `tools/writes.cljs`.

  ## epoch-id is `:any`

  The `epoch-id` arg is parsed as EDN, not assumed `string?` — the
  reference epoch runtime emits INTEGER epoch-ids (the same `:any`
  contract that drives the cursor fix). A bare integer string (\"7\")
  reads as the integer 7; a keyword reads as a keyword; an unreadable
  value returns `:invalid-epoch-id`.

  Restore is a query against a finite per-frame history; it can fail
  for the documented `:rf.epoch/*` reasons (no-such-epoch, ring
  age-out, restore-during-drain — see spec/Tool-Pair.md §Restore
  failure modes). The runtime primitive returns `false` on any failure
  (the frame's app-db is unchanged); we surface that as
  `{:ok? false :reason :restore-rejected}`."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.writes :as writes]))

(defn- parse-epoch-id
  "Parse the `epoch-id` MCP arg as EDN. Returns `[:ok parsed]` for any
  present, readable value (integer / keyword / string — `:epoch-id` is
  `:any`); `[:err reason]` for an absent or unreadable value."
  [epoch-id-str]
  (let [trimmed (some-> epoch-id-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err :missing-epoch-id]

      :else
      (let [parsed (try (cljs.reader/read-string trimmed)
                        (catch :default _ ::reader-fail))]
        (if (= ::reader-fail parsed)
          [:err :invalid-epoch-id]
          [:ok parsed])))))

(defn restore-epoch-tool [conn raw-args]
  (if-not (writes/writes-allowed?)
    (js/Promise.resolve (writes/disabled-result "restore-epoch"))
    (let [epoch-id-str (wire/arg raw-args :epoch-id)
          build-id     (wire/arg-build conn raw-args)
          frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
          [tag payload] (parse-epoch-id epoch-id-str)]
      (case tag
        :err
        (js/Promise.resolve
          (wire/err-text
            {:ok?      false
             :reason   payload
             :epoch-id epoch-id-str
             :hint     "usage: restore-epoch {epoch-id '<id>' [frame :foo]}. The id is parsed as EDN — an integer id like 7 may be passed as \"7\"."}))

        :ok
        (let [epoch-id payload
              ;; restore-epoch's runtime arglist is ([epoch-id]
              ;; [epoch-id frame-id]) — the frame is the SECOND arg.
              call (if frame
                     (ef/rt-call 'restore-epoch epoch-id frame)
                     (ef/rt-call 'restore-epoch epoch-id))
              form (ef/emit call)]
          (-> (probe/ensure-runtime! conn build-id)
              (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
              (.then (fn [v]
                       ;; Per rf2-6yqdl: the runtime now returns a
                       ;; structured envelope on success carrying
                       ;; `:ok? :restored? :epoch-id :frame
                       ;; :cascade-summary :unreplayable-effects`.
                       ;; Failure remains the legacy `false` so older
                       ;; runtimes still surface as a clean reject
                       ;; envelope here.
                       (wire/ok-text
                         (cond
                           (map? v)
                           v

                           v
                           {:ok?       true
                            :restored? true
                            :epoch-id  epoch-id
                            :frame     frame}

                           :else
                           {:ok?       false
                            :restored? false
                            :reason    :restore-rejected
                            :epoch-id  epoch-id
                            :frame     frame
                            :hint      (str "restore-epoch returned false — the epoch-id is not in the "
                                            "ring, or a drain is in flight. Read the structured reason "
                                            "with (re-frame.trace.tooling/trace-buffer {:op-type :error}) "
                                            "filtered to :rf.epoch/*.")}))))
              (.catch (fn [err] (probe/err->result :restore-epoch-failed err)))))))))
