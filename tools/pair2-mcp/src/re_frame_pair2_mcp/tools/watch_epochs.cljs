(ns re-frame-pair2-mcp.tools.watch-epochs
  "Tool: watch-epochs — pull-mode polling with predicate filter.

  The bash version streams via repeated `emit`s on stdout. MCP tools
  aren't streaming — we return one bundle of matches per call. Callers
  that want a tight loop call us repeatedly with the same `since-id`.

  Cursor pagination (rf2-kbqq3): a single poll's matches vector is
  bounded by `:limit` (default 50). When more matches remain in the
  current ring, `:next-cursor` rides the response — the agent calls
  back with the cursor to consume the remainder. The cursor is the
  opaque sibling of the `:since-id` arg; both can be used, but
  `:cursor` takes precedence (it carries sticky pred/frame state)."
  (:require [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.cursor :as cursor]
            [re-frame-pair2-mcp.tools.dedup :as dedup]
            [re-frame-pair2-mcp.tools.sensitive :as sensitive]))

(defn watch-epochs-tool [conn args]
  (let [build-id  (wire/arg-build args)
        frame     (some-> (wire/arg args :frame) keyword)
        since-id  (wire/arg args :since-id)
        incl?     (sensitive/include-sensitive? args)
        mode      (dedup/parse-epochs-mode (wire/arg args :epochs-mode))
        dedup?    (dedup/parse-dedup-arg (wire/arg args :dedup))
        limit     (cursor/parse-limit-arg (wire/arg args :limit))
        pred-map  (when-let [p (wire/arg args :pred)] (js->clj p :keywordize-keys true))
        cursor-in (cursor/decode-cursor (wire/arg args :cursor))]
    (if (= cursor-in ::cursor/malformed)
      (js/Promise.resolve
        (cursor/cursor-stale-result "watch-epochs" {:requested-id nil}))
      (let [;; Cursor's :after-id overrides bare :since-id when both
            ;; are supplied. Both shapes share semantics; cursor wins
            ;; so the agent's continuation flow stays consistent.
            effective-after (or (:after-id cursor-in) since-id)
            sticky-frame    (or (:frame cursor-in) frame)
            frame-arg       (if sticky-frame (str " " (pr-str sticky-frame)) "")
            form (str "(let [r (re-frame-pair2.runtime/epochs-since "
                      (pr-str effective-after) frame-arg ")"
                      "      matches (filterv #(re-frame-pair2.runtime/epoch-matches? "
                      (pr-str (or pred-map {})) " %) (:epochs r))"
                      "      page (vec (take " limit " matches))"
                      "      next-id (when (< (count page) (count matches))"
                      "                (:epoch-id (last page)))]"
                      "  {:matches page"
                      "   :id-aged-out? (:id-aged-out? r)"
                      "   :requested-id (:requested-id r)"
                      "   :head-id (:head-id r)"
                      "   :next-id next-id"
                      "   :remaining (max 0 (- (count matches) (count page)))})")]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then
              (fn [v]
                (let [v          (if (map? v) v {})
                      aged-out?  (:id-aged-out? v)]
                  (if (and aged-out? (some? effective-after))
                    (cursor/cursor-stale-result "watch-epochs"
                                                {:requested-id (or (:requested-id v) effective-after)
                                                 :head-id      (:head-id v)})
                    (let [matches        (vec (:matches v))
                          [kept dropped] (sensitive/strip-sensitive matches incl?)
                          encoded        (dedup/diff-encode-epochs kept mode)
                          deduped        (dedup/dedup-value encoded dedup?)
                          ;; :elided-large parity per Conventions §Cross-MCP
                          ;; indicator-field vocabulary (rf2-2499j). Same
                          ;; rationale as trace-window — no elision walk on
                          ;; epoch records today; the scaffolding emits the
                          ;; slot the moment a future revision wires it.
                          elided         (base-vocab/count-elided-markers deduped)
                          next-id        (:next-id v)
                          next-cursor    (cursor/encode-cursor
                                           (when next-id
                                             {:v        1
                                              :after-id next-id
                                              :ms       nil
                                              :until-ms nil
                                              :frame    sticky-frame}))
                          remaining      (or (:remaining v) 0)
                          base           (cond-> {:ok?           true
                                                  :head-id       (:head-id v)
                                                  :id-aged-out?  (boolean aged-out?)}
                                           (:requested-id v) (assoc :requested-id (:requested-id v)))]
                      (wire/ok-text (wire/with-indicators
                                      (assoc base
                                             :matches             deduped
                                             :limit               limit
                                             :count               (count encoded)
                                             :epochs-mode         mode
                                             :dedup               dedup?
                                             :has-more?           (some? next-cursor)
                                             :estimated-remaining remaining
                                             :next-cursor         next-cursor)
                                      {:dropped dropped :elided elided})))))))
            (.catch (fn [err] (probe/err->result :watch-failed err))))))))
