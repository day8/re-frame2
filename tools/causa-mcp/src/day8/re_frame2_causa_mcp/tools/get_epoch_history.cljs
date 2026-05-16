(ns day8.re-frame2-causa-mcp.tools.get-epoch-history
  "Tool: `get-epoch-history` — per-frame epoch history (rf2-8xzoe.15,
  T-Insp-2 of the causa-mcp inspection tranche).

  Returns a vector of `:rf/epoch-record` per Tool-Pair §Time-travel —
  oldest-first by default. Cursor pagination over the default depth-50
  page lets the agent walk the full history without overflowing the
  per-call token budget. The epoch-slice dedup factor (5-10×) lives in
  the runtime accessor's elide-wire-value call; this tool counts the
  resulting markers and stamps the indicator.

  ## Wire-boundary contract

  All three mechanisms fire (this is a trace-stream-shaped tool — the
  epoch's `:sensitive?` rollup is stamped by the runtime per rf2-isdwf):

  1. **B-1 privacy** — `:sensitive? true` epochs are dropped by
     default; `:include-sensitive? true` opts back in. The
     `:dropped-sensitive` counter rides on the envelope when non-zero.
  2. **W-6 size elision** — counted on the kept epochs (the runtime
     walker already substituted markers on the per-record `:db-before`
     / `:db-after` slots).
  3. **W-1 token cap** — dispatcher-level. Cap-reached hint
     `:paginate` (re-call with `:cursor` to resume mid-stream).

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:frame` | keyword | nil | scope to one frame; nil → resolve sole frame |
  | `:limit` | int | 50 | max epochs per page (Tool-Pair depth-50 default) |
  | `:cursor` | string | nil | opaque server-managed cursor to resume mid-stream |
  | `:include-sensitive?` | bool | false | opt back in to `:sensitive? true` items |
  | `:include-large?` | bool | false | passes to the runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      {:ok? true
       :frame <kw>
       :epochs <vec of :rf/epoch-record>
       :count <int>
       :total <int>
       :limit <int>
       :next-cursor <string?>     ; only when more pages remain
       :dropped-sensitive <int?>
       :elided-large <int?>}

  ## Cursor shape

  The cursor is an opaque base64-encoded EDN map carrying the resume-
  point's `:after-id` (the epoch-id of the last item on the prior
  page). Servers may rotate the cursor format; agents pass it back
  verbatim. The runtime accessor returns the FULL history (the
  `:epoch-history` surface is the registered Tool-Pair contract); this
  tool slices it cursor-relative on the MCP-server side so the
  page-walk doesn't roundtrip a full epoch ring twice.

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #15. Tool-Pair binding §Time-travel. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [cljs.reader :as edn]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(def ^:const default-limit
  "Tool-Pair §Time-travel depth-50 default page size. An agent reading
  per-frame epoch history expects 50 records on the first call."
  50)

;; ---------------------------------------------------------------------------
;; Cursor encode/decode — opaque base64-EDN.
;;
;; Today the cursor carries `:after-id` (the last epoch-id of the
;; prior page). Future extensions can add `:limit` (sticky page-size)
;; or `:until-id` (sticky window upper-bound) without breaking
;; backwards compatibility — readers ignore unknown keys.
;; ---------------------------------------------------------------------------

(defn encode-cursor
  "Encode the resume-point map as a base64-EDN opaque string. Returns
  nil when `m` is nil. Pure — tests pin the round-trip."
  [m]
  (when (some? m)
    (-> m pr-str (js/Buffer.from "utf8") (.toString "base64"))))

(defn decode-cursor
  "Decode a cursor string into the resume-point map. Returns nil for
  a nil / blank string; returns `::malformed` for parse failures so
  the tool can surface a structured `:cursor-stale` envelope rather
  than silently treating a malformed cursor as 'start from the top'."
  [s]
  (cond
    (or (nil? s) (and (string? s) (zero? (count s))))
    nil

    (string? s)
    (try
      (let [decoded (.toString (js/Buffer.from s "base64") "utf8")
            parsed  (edn/read-string decoded)]
        (if (map? parsed) parsed ::malformed))
      (catch :default _ ::malformed))

    :else ::malformed))

(defn- slice-after-id
  "Drop epochs up to and including `after-id`. If `after-id` isn't
  present in the epoch vector, returns `[::aged-out vec]` so the tool
  can surface `:cursor-stale`. Otherwise returns `[::ok remainder]`."
  [epochs after-id]
  (if (nil? after-id)
    [::ok epochs]
    (let [idx (->> epochs
                   (keep-indexed (fn [i e] (when (= after-id (:epoch-id e)) i)))
                   first)]
      (if (nil? idx)
        [::aged-out epochs]
        [::ok (vec (subvec epochs (inc idx)))]))))

(defn build-form
  "Build the eval-form addressing the runtime accessor. Pure."
  [opts]
  (-> (ef/rt-call 'get-epoch-history opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :frame :epochs <vec> :count}`
  response into the MCP envelope. Pure — tests pin the cursor /
  pagination / privacy / elision logic.

  `cursor-in` is the already-decoded cursor map (or nil); `limit` is
  the per-page cap; `include-sensitive?` resolves the B-1 strip-step."
  [runtime-envelope {:keys [cursor-in limit include-sensitive?]}]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?)
      env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/get-epoch-history returned a non-envelope shape"}

      :else
      (let [all-epochs   (vec (:epochs env))
            after-id     (:after-id cursor-in)
            [tag sliced] (slice-after-id all-epochs after-id)]
        (if (= ::aged-out tag)
          ;; The cursor's after-id is no longer in the epoch ring —
          ;; surface a structured stale-cursor envelope so the agent
          ;; can recover by re-calling without a cursor (head-of-stream).
          {:ok?           false
           :reason        :cursor-stale
           :frame         (:frame env)
           :requested-id  after-id
           :hint          "The epoch the cursor pointed at has been evicted from the ring. Re-call without :cursor to resume from head."}

          (let [page-size      (or limit default-limit)
                page           (vec (take page-size sliced))
                more-remaining (> (count sliced) (count page))
                [kept dropped] (privacy/strip-sensitive page include-sensitive?)
                elided         (elision/count-elided-markers kept)
                next-id        (when more-remaining (:epoch-id (last page)))
                next-cursor    (encode-cursor (when next-id {:after-id next-id}))]
            (wire/with-indicators
              (cond-> {:ok?         true
                       :frame       (:frame env)
                       :epochs      kept
                       :count       (count kept)
                       :total       (count all-epochs)
                       :limit       page-size}
                next-cursor (assoc :next-cursor next-cursor))
              {:dropped dropped :elided elided})))))))

(defn get-epoch-history-tool
  "MCP handler for `get-epoch-history`. Returns a Promise of the
  JS-shape MCP result. Decodes the inbound `:cursor` arg (if any),
  validates it isn't malformed, and either short-circuits to
  `:cursor-malformed` or proceeds with the cursor's `:after-id`."
  [conn args]
  (let [build-id (wire/arg-build args)
        frame    (wire/arg-keyword args :frame)
        limit    (wire/arg-int args :limit default-limit)
        incl?    (privacy/parse-include-sensitive args)
        incl-large? (elision/parse-include-large args)
        cursor-raw (wire/arg args :cursor)
        cursor-in  (decode-cursor cursor-raw)]
    (cond
      (= ::malformed cursor-in)
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :cursor-malformed
                       :given  cursor-raw
                       :hint   "Pass the opaque :next-cursor from a prior response, or omit :cursor to resume from head."}))

      :else
      (let [runtime-opts (cond-> {:include-sensitive? incl?
                                  :include-large?     incl-large?}
                           frame (assoc :frame frame))
            form         (build-form runtime-opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [runtime-envelope]
                     (wire/ok-text
                       (shape-envelope runtime-envelope
                                       {:cursor-in          cursor-in
                                        :limit              limit
                                        :include-sensitive? incl?}))))
            (.catch (fn [err] (probe/err->result :get-epoch-history-failed err))))))))

(def descriptor
  {:name        "get-epoch-history"
   :description (str "Per-frame epoch history (vector of "
                     ":rf/epoch-record per Tool-Pair §Time-travel). "
                     "Oldest-first; depth-50 default page size with "
                     "opaque cursor pagination via :next-cursor. "
                     "Sensitive epochs default-dropped; pass "
                     ":include-sensitive? true to opt in.")
   :input-schema #js {:type "object"
                      :properties #js {:frame              #js {:type "string"}
                                       :limit              #js {:type "integer"}
                                       :cursor             #js {:type "string"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) get-epoch-history-tool descriptor)
