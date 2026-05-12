# 003-Tool-Catalogue

The seven MCP tools, mirroring the bash-shim catalogue.

## discover-app

Verify the shadow-cljs nREPL is reachable, inject the pair2 runtime,
and return a health summary. Run first every session.

**Args**: `build` (string, optional, default `"app"`).

**Returns**: an `:ok? true` map with `:debug-enabled?`, `:frames`,
`:coord-annotation-enabled?`, `:build-id`. Or `:ok? false` with a
`:reason` keyword if a precondition fails.

## eval-cljs

Evaluate a CLJS form in the connected browser runtime via
`shadow.cljs.devtools.api/cljs-eval`. Returns the EDN value.

**Args**: `form` (string, required), `build` (string, optional).

**Returns**: `{:ok? true :value <edn-value>}` on success;
`{:ok? false :reason :eval-error :message "..."}` on failure.

## inject-runtime

Force a re-ship of `runtime.cljs` regardless of the sentinel state.
Use after editing the runtime source.

**Args**: `build` (string, optional).

**Returns**: a health map plus `:forced? true`.

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
