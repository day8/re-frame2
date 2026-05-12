# 003-Tool-Catalogue

The seven MCP tools.

## discover-app

Verify the shadow-cljs nREPL is reachable, confirm the
`re-frame-pair2.runtime` namespace was loaded by the consumer's
shadow-cljs `:devtools :preloads`, and return a health summary. Run
first every session.

**Args**: `build` (string, optional, default `"app"`).

**Returns**: an `:ok? true` map with `:debug-enabled?`, `:frames`,
`:coord-annotation-enabled?`, `:build-id`. Or `:ok? false` with a
`:reason` keyword if a precondition fails. The most common
precondition failure on a fresh app is
`:reason :runtime-not-preloaded` — the runtime ships into the app
via shadow-cljs `:preloads`; the server probes
`js/globalThis.__re_frame_pair2_runtime` (the load-time mirror the
preload installs) and refuses with a setup hint when missing. There
is no fallback inject path; see the skill's SKILL.md §Setup for the
two-line preload entry.

## eval-cljs

Evaluate a CLJS form in the connected browser runtime via
`shadow.cljs.devtools.api/cljs-eval`. Returns the EDN value.

**Args**: `form` (string, required), `build` (string, optional).

**Returns**: `{:ok? true :value <edn-value>}` on success;
`{:ok? false :reason :eval-error :message "..."}` on failure.
`:reason :runtime-not-preloaded` if the runtime preload hasn't run.

## dispatch

Fire a re-frame2 event tagged with `:origin :pair`. Three modes:

| `sync`? | `trace`? | Mode |
|---------|----------|------|
| false   | false    | queued (`rf/dispatch`) |
| true    | false    | sync (`rf/dispatch-sync`) |
| any     | true     | trace (synchronous, returns the assembled `:rf/epoch-record`) |

**Args**: `event` (string, required — EDN-encoded event vector),
`sync` (bool), `trace` (bool), `frame` (string, e.g. `":foo"`),
`fx-overrides` (object, e.g. `{:http :stub-http}`), `build` (string).

**Returns**: the runtime's response, merged with `:mode`.

## trace-window

Return `:rf/epoch-record`s that landed in the last N ms for the
operating frame.

**Args**: `ms` (integer, default 1000), `frame` (string),
`build` (string).

**Returns**: `{:ok? true :window-ms N :count K :epochs [...]}`.

## watch-epochs

Pull-mode poll for matching epochs added after a given epoch-id.
This is the MCP equivalent of the bash `watch-epochs.sh` script's
poll loop — but MCP isn't streaming, so callers that want a tight
loop should call us repeatedly with the same `since-id`.

**Args**: `since-id` (string, optional — omit to start fresh),
`pred` (object, optional predicate filter, keys from:
`:event-id`, `:event-id-prefix`, `:effects`, `:touches-path`,
`:sub-ran`, `:render`, `:origin`, `:frame`), `frame`, `build`.

**Returns**: `{:ok? true :matches [...] :head-id "..." :id-aged-out? bool}`.

## tail-build

Wait for a hot-reload to land by polling a probe form until its
value changes from its pre-call value. Times out after `wait-ms`.

**Args**: `probe` (string — a CLJS form whose value should change
after the reload), `wait-ms` (integer, default 5000), `build` (string).

**Returns**: `{:ok? true :t <ms> :soft? false}` on a real change, or
`{:ok? false :reason :timed-out}` on timeout. If `probe` is omitted,
falls back to a 300ms soft delay (matches the bash version).

## snapshot

Coarse-grained per-frame state read in **one round-trip**. The mega-op
for investigate-X workflows that would otherwise chain 5-10 individual
reads. Server-side composition over the existing per-slice runtime
readers (`get-frame-db`, `sub-cache`, `machines` + frame-local
`[:rf/machines]`, `epoch-history`, `trace-buffer`); no parallel
implementation.

**Args**: `frames` (string `"all"` or array of frame-id strings like
`":rf/default"`, default `"all"`), `include` (array of slice names —
subset of `["app-db" "sub-cache" "machines" "epochs" "traces"]`,
default all five), `build` (string).

**Returns**:

```clojure
{:ok? true
 :frames :all|[<frame-id>...]
 :include [:app-db :sub-cache :machines :epochs :traces]
 :snapshot {<frame-id> {:app-db    {...}
                        :sub-cache {<query-v> {:value v :ref-count n}}
                        :machines  {:ids [<machine-id>...]
                                    :state {<machine-id> <snapshot>}}
                        :epochs    [<:rf/epoch-record> ...]
                        :traces    [<trace-event> ...]}
            ...}}
```

The `:machines` slice combines the global registrar's machine-id list
(`rf/machines`) with the per-frame state stash at `[:rf/machines]` in
the frame's `app-db` (per Spec 005). The `:traces` slice filters the
retain-N trace ring buffer by `:frame`. Other slices delegate
verbatim to the public per-slice surface.

Pass a smaller `include` to subset (e.g.
`{:frames "all" :include ["app-db" "epochs"]}` for a quick
"state + recent history" probe). Per-op fine-grain reads (`eval-cljs`
against `runtime/app-db-at`, `runtime/sub-cache`, etc.) stay
available — they're the right surface when you genuinely need one
slice for one frame. `snapshot` is the right surface when you don't
know yet which slice carries the answer.

`:reason :runtime-not-preloaded` if the preload hasn't run;
`:reason :snapshot-failed` (with `:message`) on any other failure.
