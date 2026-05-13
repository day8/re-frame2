(ns re-frame-pair2-mcp.tools.get-path
  "Tool: get-path — direct read-by-path against a frame's app-db
   (rf2-tygdv).

  Minimal, focused primitive. The `snapshot` tool is the right surface
  when the agent doesn't know yet which slice carries the answer;
  `get-path` is the right surface when the agent already knows the
  path. Each call is one bencode round-trip; the runtime computes
  `(get-in app-db path)` server-side so only the addressed subtree
  crosses the wire.

  Path vocabulary mirrors `get-in`: a vector of keys / indices.

  Post-eval shrink pipeline lives in
  `re-frame-pair2-mcp.tools.wire-pipeline` (rf2-ae8ie). The eval form
  ran `re-frame.core/elide-wire-value` server-side already; the
  `:scalar-value` arm of `run-wire-pipeline` just counts the
  resulting `:rf.size/large-elided` markers."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]
            [re-frame-pair2-mcp.tools.wire-pipeline :as wp]
            [re-frame-pair2-mcp.tools.probe :as probe]
            [re-frame-pair2-mcp.tools.args :as args]
            [re-frame-pair2-mcp.tools.elision :as elision]))

(defn get-path-tool [conn raw-args]
  (let [build-id  (wire/arg-build raw-args)
        frame     (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        path      (args/parse-path-arg (wire/arg raw-args :path))
        elision?  (args/parse-bool-arg raw-args :elision)]
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
      (let [snapshot-call (if frame
                            (ef/rt-call 'snapshot frame)
                            (ef/rt-call 'snapshot))
            frame-edn     (if frame
                            (pr-str frame)
                            (ef/emit (ef/rt-call 'current-frame)))
            elision-opts  (elision/elision-opts-edn elision?)
            elide-call    (if elision?
                            (str "(re-frame.core/elide-wire-value v"
                                 "  (merge {:path path :frame " frame-edn "}"
                                 "         " elision-opts "))")
                            "v")
            form (ef/emit
                   (ef/rt-let
                     ['db      snapshot-call
                      'path    path
                      'missing (ef/rt-raw "#js {}")
                      'v       (ef/rt-raw "(get-in db path missing)")]
                     (ef/rt-raw
                       (str "(if (identical? v missing)"
                            "  {:ok? false :reason :path-not-found"
                            "   :path path"
                            "   :deepest-valid-prefix"
                            "   (loop [acc [] cur db rem path]"
                            "     (cond"
                            "       (empty? rem) acc"
                            "       (and (map? cur) (contains? cur (first rem)))"
                            "       (recur (conj acc (first rem)) (get cur (first rem)) (rest rem))"
                            "       (and (sequential? cur) (integer? (first rem))"
                            "            (<= 0 (first rem) (dec (count cur))))"
                            "       (recur (conj acc (first rem)) (nth (vec cur) (first rem)) (rest rem))"
                            "       :else acc))}"
                            "  {:ok? true :exists? true :path path :value " elide-call "})"))))]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v]
                     (let [{:keys [value indicators]}
                           (wp/run-wire-pipeline v {:kind :scalar-value})
                           {:keys [elided]} indicators]
                       (wire/ok-text (wire/with-indicators
                                       (cond-> value
                                         frame           (assoc :frame frame)
                                         (:ok? value)    (assoc :elision elision?))
                                       {:elided elided})))))
            (.catch (fn [err] (probe/err->result :get-path-failed err))))))))
