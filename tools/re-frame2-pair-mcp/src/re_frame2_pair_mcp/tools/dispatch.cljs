(ns re-frame2-pair-mcp.tools.dispatch
  "Tool: dispatch — fire an event.

  ## Why parse the `event` arg as EDN (rf2-vflrg)

  The MCP `dispatch` surface is intentionally narrower than `eval-cljs`:
  the contract is `dispatch {event '[:ev/id ...]'}` — an EDN event
  vector, nothing else. An earlier shape inlined the caller's string
  verbatim into the generated eval form via `rt-raw`, which made the
  string a host-form expression rather than data. Any caller (or a
  prompt-injected agent) could supply arbitrary CLJS — `(println :pwn)`
  would have run — defeating the boundary that gates `eval-cljs`
  separately. Pre-alpha posture: this is gating the accident
  (\"I meant to dispatch an event, not execute code\") and the trivial
  malicious-string injection.

  Parsing the arg as EDN and requiring a `vector?` shape forces the
  payload to be data; it is then emitted into the runtime call through
  the normal `pr-str` path (`rt-call` arg). Unreadable strings and
  non-vector shapes return a structured `:reason :invalid-event-edn` /
  `:reason :not-an-event-vector` error rather than reaching the
  runtime."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- parse-event-edn
  "Parse the `event` MCP arg as EDN. Returns
  `[:ok parsed-vector]` on success or `[:err err-map]` on any failure.
  Two failure modes carry distinct reasons:

  - `:invalid-event-edn`     — `read-string` threw / returned nil.
  - `:not-an-event-vector`   — parsed cleanly but the shape is wrong
                                (e.g. a map, a symbol, a bare keyword).

  The hint repeats the documented usage shape so an agent that
  fat-fingers the call gets a corrective example without an extra
  round-trip."
  [event-str]
  (let [trimmed (some-> event-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err {:ok? false :reason :missing-event
             :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}]

      :else
      (let [parsed (try
                     (cljs.reader/read-string trimmed)
                     (catch :default e
                       ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok? false :reason :invalid-event-edn
                 :event event-str
                 :hint "event must be an EDN-readable vector, e.g. \"[:cart/checkout]\""}]

          (not (vector? parsed))
          [:err {:ok? false :reason :not-an-event-vector
                 :event event-str
                 :parsed-type (cond
                                (map? parsed)      :map
                                (keyword? parsed)  :keyword
                                (symbol? parsed)   :symbol
                                (sequential? parsed) :list
                                :else              :scalar)
                 :hint "event must be a vector, e.g. \"[:cart/checkout {:reason :user}]\""}]

          :else
          [:ok parsed])))))

(defn dispatch-tool [conn args]
  (let [event-str    (wire/arg args :event)
        build-id     (wire/arg-build args)
        sync?        (boolean (wire/arg args :sync))
        trace?       (boolean (wire/arg args :trace))
        frame        (wire/arg-keyword args :frame)
        fx-overrides (when-let [o (wire/arg args :fx-overrides)] (js->clj o :keywordize-keys true))
        [tag payload] (parse-event-edn event-str)]
    (case tag
      :err
      (js/Promise.resolve (wire/err-text payload))

      :ok
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            ;; rf2-vflrg: the event is now a parsed CLJS vector. Pass it
            ;; through `rt-call`'s normal arg-emit path so it is `pr-str`'d
            ;; as an EDN literal — the runtime fn receives data, not host
            ;; source. NO `rt-raw` splice on this surface.
            fn-sym     (cond trace? 'dispatch-and-collect
                             sync?  'pair-dispatch-sync!
                             :else  'pair-dispatch!)
            form (ef/emit (ef/rt-call fn-sym event-vec opts-form))
            mode (cond trace? :trace sync? :sync :else :queued)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (wire/ok-text (merge {:mode mode} (when (map? v) v)))))
            (.catch (fn [err] (probe/err->result :dispatch-failed err))))))))
