# 9. Multi-substrate and the agent loop

You have learned Story as a human workshop. This chapter steps back and shows
why the same data-shaped artifact also works for renderer experiments, MCP
tools, and coding agents. The practical takeaway is simple: Story's UI, test
runner, static docs, and agent surface are meant to read the same registry and
run the same variants.

## Substrates

A Story body names a view id and application behaviour. It does not contain
JSX, Reagent hiccup, or UIx component code. That keeps the variant
body independent from the renderer:

```clojure
(story/reg-story :story.login
  {:component  :my-app.views/login-card
   :args       {:heading "Sign in"}
   :substrates #{:reagent}})
```

The tutorial and the scaffolded path are Reagent-focused because that is where
most readers start, but the substrate set is not a Reagent set. A member of
`:substrates` names which registered render fn embeds the subject — the
authoring layer — and not which adapter `rf/init!` installed. `:hicasso` is
the member that makes the difference visible: a deck declaring
`:substrates #{:hicasso}` runs in a Reagent-hosted shell — the installed
adapter is Reagent's, the authoring is Hicasso's — resolves its own frame
through React context, and responds to writes into it. Hicasso does ship an
adapter of its own, so the two spellings coincide often enough to be worth
separating out loud: which adapter is installed is a different question from
which render fn embeds the subject. There is a worked one at
`tools/story/testbeds/hicasso_counter/`, and it rides the same PR-path play
gate every Reagent deck does.

Story installs the `:reagent` render fn itself and leaves `:uix` and
`:hicasso` to the host application, because each one's only dependency is
the host's — five lines at boot, and Story core never names them. The design
reason is still worth understanding: a variant should describe a state and
behaviour, not smuggle a renderer-specific render function into the artifact.

When a substrate cannot render a variant, Story should say that. This is the
same honesty rule as `:cannot-run`: do not pretend the tool proved or displayed
something it could not actually run.

## Story-MCP

Story-MCP is a separate tool artifact. Story core owns the registry, variants,
runtime, snapshot identity, and shell. Story-MCP owns the MCP server, JSON-RPC
transport, tool registry, and wire redaction/elision.

The canonical tool list currently includes:

| Category | Tools |
|---|---|
| Dev | `get-story-instructions`, `preview-variant`, `list-substrates` |
| Docs/read | `list-stories`, `get-story`, `get-variant`, `list-tags`, `list-modes`, `list-decorators`, `list-assertions`, `variant->edn`, `get-docs-markdown`, `explain-variant` |
| Testing | `run-variant`, `snapshot-identity`, `read-a11y-violations`, `read-failures` |
| Write, gated | `register-variant`, `unregister-variant` |

That is the agent-facing version of the same operations a human performs in
the shell: list states, preview one, run it, read failures, explain how it was
assembled, record a useful interaction, and optionally write a variant back
through the gated authoring surface.

## Skills

The repo also carries Story-related skill documentation under `skills/`. Those
skills are not a second Story model. They are operating instructions for agents
using the same Story and Story-MCP surfaces.

The useful loop is:

1. list or get the variant;
2. preview it if needed;
3. run it;
4. read failures;
5. inspect the same frame/epochs through pair or Xray tooling;
6. record or register a refined variant when writes are explicitly allowed.

That loop is only good if it mirrors the human loop. If the agent sees a
different artifact from the one the user sees in the Story shell, the tool has
split its own truth and should be fixed.

## Why this matters

The boring implementation detail is also the product point: a variant is a
data-shaped artifact in a registry.

Because of that, the same variant can be:

- rendered in Canvas mode;
- placed in a workspace grid;
- documented in Docs mode;
- run in Test mode;
- executed by `story/run` or `story/is`;
- inspected through Xray;
- shared through URL or EDN;
- addressed by Story-MCP.

That is the reason Story can aspire to more than "Storybook, but in Clojure".
Storybook is excellent at component examples. Story can use re-frame2's frames,
schemas, effects, machines, trace bus, and Xray to make examples, tests,
documentation, diagnostics, and agent workflows converge on one artifact.

There is still work to do on polish and ecosystem. Storybook has years of
mindshare and a gigantic addon world. Story should not try to win by adding
every addon-shaped idea as a new subsystem. It should win by making the core
workflow cleaner: name the state, render it honestly, test it cheaply, diagnose
it with evidence, and let humans and agents operate on the same thing.

## Useful references

- [Story API reference](api/index.md) - function and registration lookup.
- [Story-MCP API](api/mcp-surface.md) - the agent boundary from Story's side.
- [`tools/story/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story/spec) - normative Story specs.
- [`tools/story-mcp/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp/spec) - MCP server specs.
- [Xray](../xray/index.md) - the diagnostic tool Story embeds.
