(ns re-frame2-pair-mcp.tools.read-sub
  "Tool: read-sub — validated one-shot subscription read (rf2-3bu3d.7).

  Reading a subscription value is the SINGLE most common read on any
  re-frame2 app, and the pre-existing options all leaked:

    (a) raw `eval-cljs` `@(re-frame.core/subscribe [:foo args])` — works,
        but stringly (the caller must know the ns alias), UNVALIDATED (a
        typo'd sub-id silently subscribes to a non-existent sub and hands
        back nil/garbage — the typo-silent-nil mistake class), and
        un-elided (a huge value can blow the wire).
    (b) the snapshot `:sub-cache` slice — only sees subs already
        materialised in the cache; a not-yet-mounted sub is absent.

  `read-sub` is the first-class read with NO-SILENT-SWALLOW PARITY with
  `dispatch` (rf2-3bu3d.2 / rf2-3bu3d.3 / rf2-vflrg):

    1. Parse the `sub` arg as EDN ONCE server-side and require a
       `vector?` shape — host-form source (`(rf/subscribe ...)`) is
       rejected with `:reason :not-a-sub-vector`, unreadable input with
       `:reason :invalid-sub-edn`, mirroring dispatch's event-parse gate.
       The payload is data, not host source.
    2. VALIDATE the sub-id against the live `:sub` registrar (runtime-side
       `read-sub!` → `validate-sub-id`): an unknown id returns the SAME
       structured `:reason :unknown-id` + `:nearest` matches dispatch now
       returns — never a silent nil.
    3. Subscribe + deref ONCE; a deref/computation throw returns
       `:reason :sub-error` (a structured error, not a bare nil).
    4. ELIDE the value server-side through `re-frame.core/elide-wire-value`
       (the privacy posture, exactly like `snapshot`'s `:sub-cache` slice
       and `get-path`) — declared-sensitive values redact to
       `:rf/redacted`, declared-large values elide to
       `:rf.size/large-elided`, unless the `--allow-sensitive-reads` gate
       is ON and the caller opts in.

  The success envelope is `{:ok? true :query-v <v> :frame <id> :value
  <elided-value> :elision <bool>}`. Errors ride back as `:isError`
  envelopes carrying the structured `:reason` (`:not-a-sub-vector` /
  `:invalid-sub-edn` / `:missing-sub` / `:unknown-id` /
  `:ambiguous-frame` / `:sub-error`)."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

(defn- parse-sub-edn
  "Parse the `sub` MCP arg as EDN. Returns `[:ok parsed-vector]` on
  success or `[:err err-map]` on any failure — the read-side mirror of
  dispatch's `parse-event-edn`. Two failure modes carry distinct reasons:

  - `:invalid-sub-edn`  — `read-string` threw / returned nil.
  - `:not-a-sub-vector` — parsed cleanly but the shape is wrong (a map, a
                          bare keyword, a host-form list).

  The hint repeats the documented usage shape so an agent that
  fat-fingers the call gets a corrective example without a round-trip."
  [sub-str]
  (let [trimmed (some-> sub-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err {:ok? false :reason :missing-sub
             :hint "usage: read-sub {sub '[:sub/id args...]' [frame :foo]}"}]

      :else
      (let [parsed (try
                     (cljs.reader/read-string trimmed)
                     (catch :default _ ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok? false :reason :invalid-sub-edn
                 :sub sub-str
                 :hint "sub must be an EDN-readable vector, e.g. \"[:current-user]\""}]

          (not (vector? parsed))
          [:err {:ok? false :reason :not-a-sub-vector
                 :sub sub-str
                 :parsed-type (cond
                                (map? parsed)        :map
                                (keyword? parsed)    :keyword
                                (symbol? parsed)     :symbol
                                (sequential? parsed) :list
                                :else                :scalar)
                 :hint "sub must be a vector, e.g. \"[:cart/total]\" or \"[:user/by-id 42]\""}]

          :else
          [:ok parsed])))))

(defn- read-sub-form
  "Build the eval form: call the runtime `read-sub!` (validate → resolve
  frame → subscribe + deref once), then — on a hit — run the `:value`
  through `re-frame.core/elide-wire-value` server-side before it crosses
  the nREPL wire. The error envelopes (`:unknown-id` / `:ambiguous-frame`
  / `:sub-error` / `:not-a-sub-vector`) carry no `:value` slot, so the
  elision walk fires only on `(:ok? res)`.

  `walk?` (rf2-t55hxg.13) — whether the walker fires at all. NOT
  `elision?`: a bare `:elision false` still walks (sensitive redacts,
  large passes via `elision-opts`); only a deliberate full-raw opt-in
  (`:elision false` AND `:include-sensitive true`) ships the value bare."
  [query-v frame walk? frame-edn elision-opts]
  (let [read-call (if frame
                    (ef/rt-call 'read-sub! query-v frame)
                    (ef/rt-call 'read-sub! query-v))]
    (if-not walk?
      (ef/emit read-call)
      (ef/emit
        (ef/rt-let
          ['res read-call]
          (ef/rt-raw
            (str "(if (:ok? res)"
                 "  (update res :value"
                 "    (fn [v] (re-frame.core/elide-wire-value v"
                 "              (merge {:query-v (:query-v res) :frame " frame-edn "}"
                 "                     " elision-opts "))))"
                 "  res)")))))))

(defn read-sub-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        frame    (some-> (wire/arg raw-args :frame) args/->frame-keyword)
        sub-str  (wire/arg raw-args :sub)
        ;; Same `--allow-sensitive-reads` gate posture as snapshot's
        ;; `:sub-cache` slice / get-path / list-subscriptions (rf2-c2dtu /
        ;; rf2-vflrg / rf2-f1ose): gate OFF forces elision on + sensitive
        ;; off; the per-call args win only when the operator opted in.
        elision? (if (raw-state/raw-state-allowed?)
                   (args/parse-bool-arg raw-args :elision)
                   true)
        incl?    (if (raw-state/raw-state-allowed?)
                   (args/parse-bool-arg raw-args :include-sensitive)
                   false)
        ;; `elision-opts-edn` takes walker-aligned `include-large?` — MCP
        ;; `elision` true = emit markers = `:include-large?` false.
        elision-opts (elision/elision-opts-edn (not elision?) incl?)
        ;; rf2-t55hxg.13 — fail-CLOSED: walk UNLESS the caller opted into
        ;; both raw axes (`:elision false` AND `:include-sensitive true`).
        ;; A bare `:elision false` still walks so a declared-sensitive sub
        ;; value redacts to `:rf/redacted` while large content passes.
        walk?        (elision/walk-required? (not elision?) incl?)
        frame-edn    (if frame
                       (pr-str frame)
                       (ef/emit (ef/rt-call 'current-frame)))
        [tag payload] (parse-sub-edn sub-str)]
    (case tag
      :err
      (js/Promise.resolve (wire/err-text payload))

      :ok
      (let [query-v payload
            form    (read-sub-form query-v frame walk? frame-edn elision-opts)]
        (probe/eval-after-runtime-signalled!
          conn build-id form :read-sub-failed
          (fn [envelope]
            (if-not (and (map? envelope) (:ok? envelope))
              ;; Structured failure (unknown-id / ambiguous-frame /
              ;; sub-error / not-a-sub-vector) — surface verbatim as an
              ;; :isError envelope, never a silent nil success
              ;; (no-silent-swallow parity with dispatch).
              (wire/err-text (if (map? envelope)
                               envelope
                               {:ok? false :reason :unexpected-shape :value envelope}))
              ;; Hit — count the server-side elision markers over the
              ;; value and rebuild the envelope (the same `:scalar-value`
              ;; pipeline arm get-path uses).
              (let [{:keys [value indicators]}
                    (wp/run-wire-pipeline (:value envelope) {:kind :scalar-value})
                    {:keys [elided]} indicators]
                (wire/ok-text
                  (wire/with-indicators
                    (assoc envelope :value value :elision elision?)
                    {:elided elided}))))))))))
