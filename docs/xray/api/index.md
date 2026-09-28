# Xray API reference

This reference lists the functions and configuration keys a host application can use to install, open, focus and configure Xray. The tutorial chapters teach Xray by task; these pages are organised by namespace and topic, for looking things up.

Every surface here is dev tooling. Keep it out of a release build as [Keep it out of production](../01-installation.md#keep-it-out-of-production) describes.

## What is in scope

Every function and key listed in these pages is supported, and a host may rely on it. Anything not listed is internal to Xray, even where ClojureScript's public-by-default namespaces make it reachable, and may change without notice. [What this reference omits](reference.md#what-this-reference-deliberately-omits) names the internal surfaces people most often find.

Xray is the panel you look at. It has no programmatic interface for driving or inspecting the running app from another process; that is `re-frame2-pair.runtime` and the [Pair MCP server](https://github.com/day8/re-frame2/blob/main/tools/re-frame2-pair-mcp/README.md).

## Where surfaces live

Xray's user-facing surface is spread over four namespaces and one JavaScript global that the preload installs. `core` re-exports the most-used functions from the others, so one require covers the usual boot path.

| Namespace | Use when |
|---|---|
| `day8.re-frame2-xray.core` | The usual require. Opening and closing Xray (`open!` / `close!` / `toggle!` / `popout!` / `status`), the frame picker (`target-frame` / `set-target-frame!`), `focus!` for hosts such as Story, and the four most-used config setters. |
| `day8.re-frame2-xray.config` | The full configuration surface: `configure!` plus every per-key setter. Use it for a setting `core` does not re-export, or when your boot code routes all configuration through `configure!`. |
| `day8.re-frame2-xray.keybinding` | `attach!` and `detach!` for Xray's keyboard listener. Hosts that embed Xray, as Story does, use it to take the keys back. |
| `day8.re-frame2-xray.preload` | The dev-only preload for shadow-cljs's `:devtools :preloads`. You list it; you do not call anything in it. |
| `window.day8.re_frame2_xray.*` | The browser globals the preload installs, for a devtools console, a JavaScript host, or a browser automation script. |

Where a function lives in two namespaces, such as `configure!`, which `core` re-exports from `config`, either require works.

Only your dev build depends on Xray. Nothing in the framework depends on it.
