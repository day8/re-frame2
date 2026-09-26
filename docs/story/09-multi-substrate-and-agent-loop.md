# 9. Multi-substrate and the agent loop

A variant names a view id and describes a state as data, so the same
registration can render under more than one view layer, and an agent can run
it. This chapter covers substrates, the two hosts an agent can drive Story in,
and Story-MCP.

## Substrates

A story body names a view id and describes behaviour. It contains no JSX,
Reagent hiccup or UIx component code, so the same body can render under
different view layers, which Story calls substrates:

```clojure
(rf.story/reg-story :story.login
  {:component  :my-app.views/login-card
   :args       {:heading "Sign in"}
   :substrates #{:reagent}})
```

A member of `:substrates` names the registered render function that embeds the
view, which is not the same thing as the adapter `rf/init!` installed. A story
with `:substrates #{:fresco}` renders Fresco views inside a shell whose
installed adapter is Reagent's; the Fresco views find their variant's frame
through React context and respond to events dispatched into it. The testbed at
`tools/story/testbeds/fresco_counter/` is a worked example.

Story registers the `:reagent` render function itself. `:uix` and `:fresco`
depend on libraries only your app carries, so your app registers them at boot,
in a few lines. For UIx:

```clojure
(ns my-app.stories
  (:require [uix.core       :refer [$]]
            [re-frame.core  :as rf]
            [re-frame.story :as rf.story]))

(rf.story/register-substrate! :uix
  (fn [_variant-id view-id args]
    ($ (rf/view view-id) args)))
```

The render function receives the variant id, the view id and the effective
args, and returns what the shell mounts inside the variant's frame. The
`:fresco` version resolves the view the same way and creates the element with
`re-frame.fresco/as-element`; the testbed above carries it.

A variant whose `:substrates` set names more than one member renders once per
substrate, side by side, each in a cell headed with its name. A variant
without `:substrates` takes its story's. When a substrate cannot render a
variant, Story says so: a substrate nobody registered paints a red cell naming
the `register-substrate!` call it needs, as `:cannot-run` does for a step a
runner cannot perform.

## Two hosts

An agent drives Story in one of two hosts, and each host owns its own frames.

- **The story-mcp server** is a stdio process in its own JVM. It sees the
  stories loaded into that JVM, runs variants in frames it allocates there,
  and has no bridge to a browser. A browser-only read such as
  `list-substrates` or `read-a11y-violations` therefore answers with a
  capability-unavailable error, never an empty result.
- **The browser** is the app you have open. An agent reaches its Story
  registry through re-frame2-pair's `eval-cljs`, then reads, dispatches to
  and traces a variant with the ordinary pair tools, because a variant is a
  frame.

A variant id registered in both hosts names two frames with two separate
app-dbs, so run a whole loop in the host that holds the frame you care about.
The agent skills state the rule for choosing: the `re-frame2` skill for
story-mcp, in
[`story-mcp-loop.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/story-mcp-loop.md#which-host-to-use),
and the `re-frame2-pair` skill for the browser, in
[`stories.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/stories.md#which-host-to-use).
[Two surfaces, one live door](api/mcp-surface.md#two-surfaces-one-live-door)
covers the boundary in more depth.

## Story-MCP

Story-MCP is a separate artifact, `day8/re-frame2-story-mcp`. Story holds the
registry, the runtime, snapshot identity and the shell; Story-MCP adds the MCP
server, its JSON-RPC transport, the tool registry, and the redaction of values
sent to the agent.

It exposes 19 tools:

| Category | Tools |
|---|---|
| Dev | `get-story-instructions`, `preview-variant`, `list-substrates` |
| Docs/read | `list-stories`, `get-story`, `get-variant`, `list-tags`, `list-modes`, `list-decorators`, `list-assertions`, `variant->edn`, `get-docs-markdown`, `explain-variant` |
| Testing | `run-variant`, `snapshot-identity`, `read-a11y-violations`, `read-failures` |
| Write, gated | `register-variant`, `unregister-variant` |

They cover what you do in the shell: list states, preview one, run it, read
its failures, see how it was assembled, and, when writes are allowed, register
a variant. [Running the server](api/mcp-surface.md#running-the-server) shows
how to launch it against your stories.

## The agent loop

The repository's skills under `skills/` are operating instructions for agents
that use Story and Story-MCP. Run inside one of the two hosts, the loop is:

1. list or get the variant;
2. preview it if needed;
3. run it;
4. read its failures;
5. in the browser host, inspect the same frame and its epochs through the pair
   tools or Xray;
6. register a refined variant, when writes are allowed.

The agent runs the same variant the shell shows, and reads the same run result
Test mode does.

## Useful references

- [Story API reference](api/index.md) - function and registration lookup.
- [Story-MCP API](api/mcp-surface.md) - the agent boundary from Story's side.
- [`tools/story/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story/spec) - normative Story specs.
- [`tools/story-mcp/spec/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp/spec) - MCP server specs.
- [Xray](../xray/index.md) - the diagnostic tool Story embeds.
