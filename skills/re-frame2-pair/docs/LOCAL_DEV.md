# Running re-frame2-pair locally (no npm needed)

npm publishing is for distribution to other people. While developing the skill itself — or dogfooding it against a real re-frame2 app before the first release — you run it straight from a clone. Three install paths, most to least convenient.

> **Note (consolidation):** This skill now lives under
> `re-frame2/skills/re-frame2-pair/`. The example paths below assume you have
> a re-frame2 clone at `~/code/re-frame2` (or `%USERPROFILE%\code\re-frame2`)
> and are linking the `skills/re-frame2-pair/` subdirectory. Adjust the source
> path if your clone is elsewhere.

## Prerequisites on your machine

Same as the README's *Requirements*:

- The **MCP server** — the only skill-facing transport. Install it with `npm install -g @day8/re-frame2-pair-mcp` and add an `mcpServers` entry (see [`tools/re-frame2-pair-mcp/README.md`](../../../tools/re-frame2-pair-mcp/README.md)), or run it straight from this clone — see [§MCP server from a clone](#mcp-server-from-a-clone) below.
- [Claude Code](https://docs.claude.com/en/docs/claude-code).
- A re-frame2 + shadow-cljs app to exercise it against. (Optional: re-com — used as a fallback source-coord source, not required.)
- The **`re-frame2-pair.runtime` preload** on the app's `:source-paths`. From a clone (this doc's install paths) it comes for free off the linked skill dir's `preload/` — point `:source-paths` at the absolute `skills/re-frame2-pair/preload/` path. For a non-clone (npm) install, run `npm install -D @day8/re-frame2-pair` in the app first and point at `node_modules/@day8/re-frame2-pair/preload` (see the README's *Install* §). Either way the preload is **required** — `discover-app` refuses with `:runtime-loaded-but-preload-missing` without it (the normal missing-preload verdict; `:runtime-not-preloaded` is the degradation fallback the ladder returns only if it errors mid-diagnosis, and the reason the per-op marker check reports).

> **Babashka is not a skill requirement.** The retired bash shims under
> `scripts/` (and the project's own `tests/shim/` harness) exec `bb`, but
> they are not reachable from the skill's `allowed-tools:`. You only need
> [`babashka`](https://babashka.org) on `PATH` if you are running those
> shims directly for the e2e harness or ad-hoc shell use.

## MCP server from a clone

The install paths below link the **skill directory** (`SKILL.md` + `preload/`
+ `docs/` + `references/`). They do not start the **MCP server** — the only
skill-facing transport — which lives at `tools/re-frame2-pair-mcp/` outside the
skill dir. To run the server from this clone instead of waiting for an npm
release, build it once (`cd tools/re-frame2-pair-mcp && npm install && npm run
build`) and point your Claude Code `mcpServers` entry at the compiled server:

```json
{
  "mcpServers": {
    "re-frame2-pair": {
      "command": "node",
      "args": ["<repo>/tools/re-frame2-pair-mcp/out/server.js"]
    }
  }
}
```

See [`tools/re-frame2-pair-mcp/README.md`](../../../tools/re-frame2-pair-mcp/README.md)
for the exact entry point, the launch flags (`--allow-writes`,
`--allow-sensitive-reads`, `--no-eval`, `--port-file`), and port discovery.
Restart Claude Code after editing `mcpServers`.

## 0. Repo installer (recommended — links all skills)

From a re-frame2 clone, the cross-platform installer links **every** skill
(including this one) into `~/.claude/skills/` by symlink (macOS/Linux) or
directory junction (Windows, no admin needed). Edits in the repo are live
immediately; nothing to keep in sync.

```bash
# macOS / Linux:
scripts/install-skills.sh

# Windows:
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1
```

Idempotent; re-run any time. It refuses to overwrite a non-link copy without
`--force`/`-Force`, and `--check`/`-Check` verifies the links. See the repo's
[`CONTRIBUTING.md`](../../../CONTRIBUTING.md#skills--link-dont-copy).

The remaining paths below link this one skill by hand — equivalent for a
single skill, useful when you are not working from a full re-frame2 clone.

## 1. Symlink one skill by hand

Edits you make in the repo are live immediately — no copy to keep in sync.

### macOS / Linux

```bash
mkdir -p ~/.claude/skills
ln -s "$HOME/code/re-frame2/skills/re-frame2-pair" ~/.claude/skills/re-frame2-pair
```

### Windows

With Developer Mode or admin:

```powershell
New-Item -ItemType SymbolicLink `
  -Path "$env:USERPROFILE\.claude\skills\re-frame2-pair" `
  -Target "$env:USERPROFILE\code\re-frame2\skills\re-frame2-pair"
```

Without admin, use a directory junction (the primitive the repo installer
uses):

```cmd
mklink /J %USERPROFILE%\.claude\skills\re-frame2-pair %USERPROFILE%\code\re-frame2\skills\re-frame2-pair
```

Junctions behave like symlinks for read purposes; fine for skill loading.

## 2. Copy (snapshot the current state) — drifts; avoid for everyday dev

```bash
cp -r ~/code/re-frame2/skills/re-frame2-pair ~/.claude/skills/re-frame2-pair
```

Simple, but you have to re-copy after every change — and if you forget, Claude
Code silently loads a **stale** skill that drifts from the repo (this is the
exact failure the link-based installer in §0 was written to prevent). Only use
a copy when you deliberately want to pin a specific snapshot and keep iterating
on the repo without affecting Claude's view. For everyday dev, prefer §0/§1.

## 3. Project-local (only active in one app)

Same content, but under a specific target project rather than your home directory:

```bash
cd ~/some-re-frame2-app
mkdir -p .claude/skills
ln -s "$HOME/code/re-frame2/skills/re-frame2-pair" .claude/skills/re-frame2-pair
```

Useful if you only want the skill loaded when you open the specific app you're debugging — and useful for testing what the project-local install flow feels like before anyone ships the skill.

## Invoking it in Claude Code

Once the skill directory is in place:

- **Implicit**: ask about your running re-frame2 app in natural language (*"what's in `app-db` under `:cart`?"*). Claude auto-matches the skill's description.
- **Explicit**: type `/re-frame2-pair` or name it in a prompt (*"Using re-frame2-pair, trace `[:cart/apply-coupon ...]`"*).

First use of a session calls the `discover-app` MCP tool — that connects to your shadow-cljs nREPL (the MCP server discovers the port itself), verifies prerequisites, and probes the preloaded `re-frame2-pair.runtime` marker.

## Dev loop: iterating on the skill itself

The power of the symlink approach is that editing `SKILL.md` / `references/*.md` / `preload/re_frame2_pair/runtime.cljs` in the repo takes effect immediately:

| You edited... | What Claude sees after your next prompt |
|---|---|
| `SKILL.md` frontmatter or body | New vocabulary / recipes on next invocation (may need to restart the Claude Code session for the description change to be re-indexed). |
| `references/*.md` | Picked up on the next leaf load — no restart needed. |
| `preload/re_frame2_pair/runtime.cljs` | shadow-cljs hot-reloads the namespace into the running app as soon as you save (it's on the consumer's `:source-paths`). No re-inject command. If the changes touch `defonce`'d state (listeners, atoms), reload the page once. |
| The MCP server (`tools/re-frame2-pair-mcp/`) | Rebuild it (`npm run build` in that dir) and restart Claude Code so it reconnects to the new server. |
| The retired shell shims (`scripts/*.sh`, `scripts/ops.clj`) | Not reachable from the skill — they only affect the project's own `tests/shim/` e2e harness. A re-run of `bb ops.clj` (or the `.sh` wrapper) picks them up. |

## Troubleshooting

### The skill doesn't appear in `/` completion

- Confirm the directory landed where Claude Code looks: `ls ~/.claude/skills/re-frame2-pair/` (or the project-local equivalent).
- Confirm `SKILL.md` is at the top level of that directory, not nested.
- Restart Claude Code — it reads the skill registry at session start.
- Check the skill name in `SKILL.md`'s frontmatter — it must match the directory name (`re-frame2-pair`).

### `babashka-missing` (harness-only — not the skill path)

You will only see this if you run the retired bash shims (`scripts/*.sh`, `tests/shim/`) directly for the e2e harness — the skill itself never execs `bb`. It means `bb` isn't on `PATH`. Verify with `which bb` (macOS/Linux) or `where bb` (Windows). Install:

- macOS: `brew install borkdude/brew/babashka`
- Linux / Windows: [babashka install guide](https://github.com/babashka/babashka#installation)

Restart the shell so the new `PATH` takes effect.

### `discover-app` can't find the nREPL

`discover-app` returns a port-not-found / connection error. The MCP server discovers the port automatically (it scans `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, `.nrepl-port` at the CWD and under `implementation/`, and picks the most-recently-modified one). If it misses, first start your dev build:

```bash
npx shadow-cljs watch <build-id>
```

...and make sure nREPL is enabled for the build.

If a build *is* running but discovery picks the wrong port (`connection refused`, or it attaches to a stale build), set the override explicitly — pass `--port-file <abs>` to the server or set `SHADOW_CLJS_NREPL_PORT=<live-port>`, which bypasses file discovery entirely and is independent of the working directory.

### `:debug-disabled`

`re-frame.interop/debug-enabled?` is false (production build, or `goog.DEBUG` was forced off). Trace and epoch surfaces are elided. For a dev build this should be true automatically; for a release/staging build you'll need to flip the closure-define.

### `:no-frames-registered`

The app hasn't established its app frame yet (or the only frame was destroyed). `init!` installs only the adapter and creates no frame under EP-0002, so calling it won't help — wait for boot, or have the app register its frame at the root (e.g. a root `frame-provider {:id ...}`, or `reg-frame`).

### `:ambiguous-frame`

Two-plus app frames are registered and the session hasn't pinned one. Pin one with `set-operating-frame {frame: ":foo"}`, or pass `frame: ":foo"` per call. Both writes and reads refuse rather than guess: the dedicated `snapshot` / `get-path` / `dispatch` tools refuse, and the lower-level read helpers (`subs-sample` / `read-sub!` / `sub-cache-info`) return `:reason :ambiguous-frame` rather than silently reading `:rf/default`.

### Watch ops don't stream anything

Two likely causes:

- **No epoch history yet.** `(rf/epoch-history :rf/default)` returns `[]` until the app dispatches at least one event. Click around or fire one synthetic dispatch.
- **No activity matches the predicate**. Try `watch-epochs {}` with no predicate (or `subscribe {topic: "epoch"}`) to confirm the transport works, then add filters.

### DOM ops return `{:src nil}`

Two preconditions, at least one must hold:

- a debug build (`interop/debug-enabled?` / `goog.DEBUG`) with the element produced by a **registered view** (`reg-view`) on a DOM-capable adapter — re-frame2 stamps `data-rf2-source-coord` on registered-view roots automatically there (mandatory, no `configure!` opt-in), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

If neither, `dom/source-at` returns `:reason :source-coord-annotation-disabled` for every element — check registered-view coverage, adapter DOM support, the debug build, or a re-com `:src (at)` fallback.

### Changes to `runtime.cljs` aren't taking effect

shadow-cljs hot-reloads namespaces under `:source-paths` on save. If your edits aren't landing:

1. Confirm `preload/` is on `:source-paths` in `shadow-cljs.edn` (see `SKILL.md` §Setup).
2. Check the shadow-cljs console for a compile error on the namespace.
3. Edits to `defonce`'d state (the trace/epoch listeners, the global marker) don't re-run — reload the page once.

### `:runtime-loaded-but-preload-missing` (and per-op `:runtime-not-preloaded`)

The skill's runtime namespace isn't loaded into your app. `discover-app`'s normal verdict for this is `:runtime-loaded-but-preload-missing` (a runtime is live but the marker is absent); the per-op marker check reports the runtime-side `:runtime-not-preloaded`. Either way: add the two-line preload setup in `SKILL.md` §Setup and reload the page (or wait for the next shadow-cljs rebuild). (`:runtime-not-preloaded` is also the degradation fallback `discover-app` returns when the ladder itself errors mid-diagnosis — suspect a flaky nREPL connection there.)

## Uninstall / reset

```bash
# symlink or junction:
rm ~/.claude/skills/re-frame2-pair

# copy:
rm -rf ~/.claude/skills/re-frame2-pair
```

Restart Claude Code. The skill disappears from completion; no residual state.
