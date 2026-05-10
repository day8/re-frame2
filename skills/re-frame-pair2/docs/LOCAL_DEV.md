# Running re-frame-pair2 locally (no npm needed)

npm publishing is for distribution to other people. While developing the skill itself — or dogfooding it against a real re-frame2 app before the first release — you run it straight from a clone. Three install paths, most to least convenient.

> **Note (consolidation):** This skill now lives under
> `re-frame2/skills/re-frame-pair2/`. The example paths below assume you have
> a re-frame2 clone at `~/code/re-frame2` (or `%USERPROFILE%\code\re-frame2`)
> and are linking the `skills/re-frame-pair2/` subdirectory. Adjust the source
> path if your clone is elsewhere.

## Prerequisites on your machine

Same as the README's *Requirements*:

- [`babashka`](https://babashka.org) on `PATH` — the shell shims exec `bb` regardless of how the skill is installed.
- [Claude Code](https://docs.claude.com/en/docs/claude-code).
- A re-frame2 + shadow-cljs app to exercise it against. (Optional: re-com — used as a fallback source-coord source, not required.)

## 1. Symlink (recommended for dev)

Edits you make in the repo are live immediately — no copy to keep in sync.

### macOS / Linux

```bash
mkdir -p ~/.claude/skills
ln -s "$HOME/code/re-frame2/skills/re-frame-pair2" ~/.claude/skills/re-frame-pair2
```

### Windows

With Developer Mode or admin:

```powershell
New-Item -ItemType SymbolicLink `
  -Path "$env:USERPROFILE\.claude\skills\re-frame-pair2" `
  -Target "$env:USERPROFILE\code\re-frame2\skills\re-frame-pair2"
```

Without admin, use a directory junction:

```cmd
mklink /J %USERPROFILE%\.claude\skills\re-frame-pair2 %USERPROFILE%\code\re-frame2\skills\re-frame-pair2
```

Junctions behave like symlinks for read purposes; fine for skill loading.

## 2. Copy (snapshot the current state)

```bash
cp -r ~/code/re-frame2/skills/re-frame-pair2 ~/.claude/skills/re-frame-pair2
```

Simple, but you have to re-copy after every change. Useful if you want to pin a specific commit and keep iterating on the repo itself without affecting Claude's view of the skill.

## 3. Project-local (only active in one app)

Same content, but under a specific target project rather than your home directory:

```bash
cd ~/some-re-frame2-app
mkdir -p .claude/skills
ln -s "$HOME/code/re-frame2/skills/re-frame-pair2" .claude/skills/re-frame-pair2
```

Useful if you only want the skill loaded when you open the specific app you're debugging — and useful for testing what the project-local install flow feels like before anyone ships the skill.

## Invoking it in Claude Code

Once the skill directory is in place:

- **Implicit**: ask about your running re-frame2 app in natural language (*"what's in `app-db` under `:cart`?"*). Claude auto-matches the skill's description.
- **Explicit**: type `/re-frame-pair2` or name it in a prompt (*"Using re-frame-pair2, trace `[:cart/apply-coupon ...]`"*).

First use of a session runs `scripts/discover-app.sh` — that connects to your shadow-cljs nREPL, verifies prerequisites, and injects the runtime namespace.

## Dev loop: iterating on the skill itself

The power of the symlink approach is that editing `SKILL.md` / `scripts/runtime.cljs` / `scripts/ops.clj` in the repo takes effect immediately:

| You edited... | What Claude sees after your next prompt |
|---|---|
| `SKILL.md` frontmatter or body | New vocabulary / recipes on next invocation (may need to restart the Claude Code session for the description change to be re-indexed). |
| `scripts/ops.clj` | Next `bb` invocation picks it up — no action needed. |
| `scripts/runtime.cljs` | The injected code in the browser is **stale** until you explicitly re-push it. Run `scripts/inject-runtime.sh` in the connected session; it now force-reinjects regardless of the session sentinel so your edits land without a full page refresh. |
| Shell shims (`*.sh`) | Next invocation picks them up. |

## Troubleshooting

### The skill doesn't appear in `/` completion

- Confirm the directory landed where Claude Code looks: `ls ~/.claude/skills/re-frame-pair2/` (or the project-local equivalent).
- Confirm `SKILL.md` is at the top level of that directory, not nested.
- Restart Claude Code — it reads the skill registry at session start.
- Check the skill name in `SKILL.md`'s frontmatter — it must match the directory name (`re-frame-pair2`).

### `babashka-missing` error from `discover-app.sh`

`bb` isn't on `PATH`. Verify with `which bb` (macOS/Linux) or `where bb` (Windows). Install:

- macOS: `brew install borkdude/brew/babashka`
- Linux / Windows: [babashka install guide](https://github.com/babashka/babashka#installation)

Restart the shell (and Claude Code) so the new `PATH` takes effect.

### `:nrepl-port-not-found`

`discover-app.sh` couldn't locate `target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`, or `$SHADOW_CLJS_NREPL_PORT`. Start your dev build:

```bash
npx shadow-cljs watch <build-id>
```

...and make sure nREPL is enabled for the build.

### `:debug-disabled`

`re-frame.interop/debug-enabled?` is false (production build, or `goog.DEBUG` was forced off). Trace and epoch surfaces are elided. For a dev build this should be true automatically; for a release/staging build you'll need to flip the closure-define.

### `:no-frames-registered`

The app hasn't called `(rf/init!)` yet (or the only frame was destroyed). Wait for boot or call `(rf/init!)` manually.

### `:ambiguous-frame`

Multiple frames are registered and the session hasn't selected one. Use `frames/select` or pass `--frame :foo` per call. Reads proceed against `:rf/default` after warning; mutating ops refuse.

### Watch ops don't stream anything

Two likely causes:

- **No epoch history yet.** `(rf/epoch-history :rf/default)` returns `[]` until the app dispatches at least one event. Click around or fire one synthetic dispatch.
- **No activity matches the predicate**. Try `scripts/watch-epochs.sh --count 5` with no predicate to confirm the transport works, then add filters.

### DOM ops return `{:src nil}`

Two preconditions, at least one must hold:

- `(rf/configure :source-coords {:annotate-dom? true})` enabled at startup, *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

If neither, `dom/source-at` returns `:reason :source-coord-annotation-disabled` for every element.

### Changes to `runtime.cljs` aren't taking effect

The session sentinel fast-path in `discover-app.sh` skips re-injection if the runtime is already present. To push edits into a live session, run `scripts/inject-runtime.sh` — it force-reinjects regardless.

## Uninstall / reset

```bash
# symlink or junction:
rm ~/.claude/skills/re-frame-pair2

# copy:
rm -rf ~/.claude/skills/re-frame-pair2
```

Restart Claude Code. The skill disappears from completion; no residual state.
