(ns re-frame-pair2-mcp.tools.trace-window
  "Tool: trace-window — epochs in the last N ms.

  Cursor pagination (rf2-kbqq3): the response is bounded at `:limit`
  items (default 50). When more remain, `:next-cursor` is non-nil. The
  cursor encodes a sticky `:until-ms` so subsequent pages see the same
  window the first call did — fresh epochs landing during pagination
  don't sneak in mid-iteration.

  Post-eval shrink pipeline lives in
  `re-frame-pair2-mcp.tools.wire-pipeline` (rf2-ae8ie). This tool body
  builds the eval form, awaits the runtime response, and routes the
  epoch vector through `run-wire-pipeline` with `:kind :epoch-vector`."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.wire-pipeline :as wp]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.cursor :as cursor]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]))

(defn trace-window-tool [conn args]
  (let [ms        (or (wire/arg args :ms) 1000)
        build-id  (wire/arg-build args)
        frame     (some-> (wire/arg args :frame) keyword)
        incl?     (sensitive/include-sensitive? args)
        mode      (dedup/parse-epochs-mode (wire/arg args :epochs-mode))
        dedup?    (dedup/parse-dedup-arg (wire/arg args :dedup))
        limit     (cursor/parse-limit-arg (wire/arg args :limit))
        cursor-in (cursor/decode-cursor (wire/arg args :cursor))]
    (if (= cursor-in ::cursor/malformed)
      (js/Promise.resolve
        (cursor/cursor-stale-result "trace-window" {:requested-id nil}))
      (let [after-id  (:after-id cursor-in)
            ;; Sticky window upper-bound: encoded on the first call so
            ;; subsequent pages see the SAME window the first call did.
            until-ms  (or (:until-ms cursor-in) (js/Date.now))
            ;; Sticky ms — agent's first-call value drives all pages.
            window-ms (or (:ms cursor-in) ms)
            cutoff-ms (- until-ms window-ms)
            sticky-frame (or (:frame cursor-in) frame)
            frame-form (when sticky-frame (str " " (pr-str sticky-frame)))
            ;; Server-side slice: pull the history, drop up-to-cursor,
            ;; filter to the window, take `limit`. The runtime ships
            ;; only what the page needs — not the whole history.
            form (str "(let [hist (vec (re-frame-pair2.runtime/epoch-history"
                      (or frame-form "") "))"
                      " after-id " (pr-str after-id)
                      " aged-out? (and after-id (not-any? #(= after-id (:epoch-id %)) hist))"
                      " sliced (if aged-out? []"
                      "          (if after-id"
                      "            (vec (rest (drop-while #(not= after-id (:epoch-id %)) hist)))"
                      "            hist))"
                      " filtered (filterv #(and (>= (or (:committed-at %) 0) " cutoff-ms ")"
                      "                         (<= (or (:committed-at %) 0) " until-ms "))"
                      "                   sliced)"
                      " page (vec (take " limit " filtered))"
                      " next-id (when (< (count page) (count filtered))"
                      "           (:epoch-id (last page)))]"
                      " {:epochs page"
                      "  :id-aged-out? aged-out?"
                      "  :requested-id after-id"
                      "  :head-id (some-> hist peek :epoch-id)"
                      "  :next-id next-id"
                      "  :remaining (max 0 (- (count filtered) (count page)))})")]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then
              (fn [v]
                (let [v          (if (map? v) v {})
                      aged-out?  (:id-aged-out? v)
                      raw-epochs (vec (:epochs v))]
                  (if aged-out?
                    (cursor/cursor-stale-result "trace-window"
                                                {:requested-id (:requested-id v)
                                                 :head-id      (:head-id v)})
                    (let [{:keys [value indicators]}
                          (wp/run-wire-pipeline raw-epochs
                                                {:kind   :epoch-vector
                                                 :incl?  incl?
                                                 :mode   mode
                                                 :dedup? dedup?})
                          {:keys [dropped elided count]} indicators
                          next-id     (:next-id v)
                          next-cursor (cursor/encode-cursor
                                        (when next-id
                                          {:v        1
                                           :after-id next-id
                                           :ms       window-ms
                                           :until-ms until-ms
                                           :frame    sticky-frame}))
                          has-more?   (some? next-cursor)
                          remaining   (or (:remaining v) 0)]
                      (wire/ok-text (wire/with-indicators
                                      {:ok?                 true
                                       :window-ms           window-ms
                                       :until-ms            until-ms
                                       :count               count
                                       :limit               limit
                                       :epochs-mode         mode
                                       :dedup               dedup?
                                       :epochs              value
                                       :has-more?           has-more?
                                       :estimated-remaining remaining
                                       :next-cursor         next-cursor}
                                      {:dropped dropped :elided elided})))))))
            (.catch (fn [err] (probe/err->result :trace-failed err))))))))
