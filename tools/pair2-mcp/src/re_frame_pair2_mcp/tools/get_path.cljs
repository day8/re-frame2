(ns re-frame-pair2-mcp.tools.get-path
  "Tool: get-path — direct read-by-path against a frame's app-db
   (rf2-tygdv).

  Minimal, focused primitive. The `snapshot` tool is the right surface
  when the agent doesn't know yet which slice carries the answer;
  `get-path` is the right surface when the agent already knows the
  path. Each call is one bencode round-trip; the runtime computes
  `(get-in app-db path)` server-side so only the addressed subtree
  crosses the wire.

  Path vocabulary mirrors `get-in`: a vector of keys / indices."
  (:require [re-frame.mcp-base.vocab :as base-vocab]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.elision :as elision]))

(defn get-path-tool [conn raw-args]
  (let [build-id  (wire/arg-build raw-args)
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        path      (args/parse-path-arg (wire/arg raw-args :path))
        elision?  (elision/parse-elision-arg (wire/arg raw-args :elision))]
    (cond
      (nil? path)
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-path
                        :hint "usage: get-path {path '[:cart :items 0 :sku]' [frame :rf/default]}"}))

      :else
      ;; Server-side eval form: call `snapshot` (full db for the frame)
      ;; then `get-in` with a missing sentinel, so we can distinguish
      ;; `path-not-found` from a path that legitimately points at nil.
      ;; The deepest-valid-prefix loop is inlined so a stale runtime
      ;; (no helper) still answers correctly.
      ;;
      ;; Elision wiring (rf2-urjnc): once `get-in` resolves the value,
      ;; we run it through `re-frame.core/elide-wire-value` so a
      ;; large / sensitive slot returns the marker (with a handle the
      ;; agent can drill into) rather than the raw bytes. The walker
      ;; reads the live `[:rf/elision]` registry from the frame's
      ;; app-db, so it must run app-side. Passing `:path path` makes
      ;; the marker's `:handle` slot carry `[:rf.elision/at <path>]`
      ;; — the agent can re-call `get-path` with a deeper segment to
      ;; drill into a non-elided child, or pass `elision false` to
      ;; bypass the walk entirely.
      (let [path-edn      (pr-str path)
            snapshot-call (if frame
                            (str "(re-frame-pair2.runtime/snapshot " (pr-str frame) ")")
                            "(re-frame-pair2.runtime/snapshot)")
            frame-edn     (if frame (pr-str frame) "(re-frame-pair2.runtime/current-frame)")
            elision-opts  (elision/elision-opts-edn elision?)
            elide-call    (if elision?
                            (str "(re-frame.core/elide-wire-value v"
                                 "  (merge {:path path :frame " frame-edn "}"
                                 "         " elision-opts "))")
                            "v")
            form (str "(let [db " snapshot-call
                      "      path " path-edn
                      "      missing #js {}"
                      "      v (get-in db path missing)]"
                      "  (if (identical? v missing)"
                      "    {:ok? false :reason :path-not-found"
                      "     :path path"
                      "     :deepest-valid-prefix"
                      "     (loop [acc [] cur db rem path]"
                      "       (cond"
                      "         (empty? rem) acc"
                      "         (and (map? cur) (contains? cur (first rem)))"
                      "         (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
                      "         (and (sequential? cur) (integer? (first rem))"
                      "              (<= 0 (first rem) (dec (count cur))))"
                      "         (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
                      "         :else acc))}"
                      "    {:ok? true :exists? true :path path :value " elide-call "}))")]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     ;; :elided-large indicator-field per Conventions
                     ;; §Cross-MCP indicator-field vocabulary (rf2-2499j).
                     ;; The eval form ran `re-frame.core/elide-wire-value`
                     ;; over the resolved `:value` (when elision was
                     ;; on), so the response may carry one or more
                     ;; `:rf.size/large-elided` markers. Count them on
                     ;; the envelope so the agent sees the elision
                     ;; footprint without diffing the payload.
                     (let [elided (base-vocab/count-elided-markers v)]
                       (wire/ok-text (cond-> v
                                       frame          (assoc :frame frame)
                                       (:ok? v)       (assoc :elision elision?)
                                       (pos? elided)  (assoc :elided-large elided))))))
            (.catch (fn [err] (probe/err->result :get-path-failed err))))))))
