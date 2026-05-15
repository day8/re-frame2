---
name: story-browser-testbed-invariants
summary: Durable Story browser-testbed lessons for workers touching Story, Story MCP, CI, or browser gates.
when_to_use: Before changing Story browser tests, Story feature-load, Story shell panels, recorder/snapshot behavior, or CI jobs that run Story browser gates.
---

# Story Browser Testbed Invariants

## Trigger

Use this note when dispatching or reviewing work on:

- `tools/story/**` browser tests or testbeds;
- Story feature-load and Playwright runners;
- Story shell panels, recorder, share, snapshot identity, or hot-reload watch;
- CI path filters or jobs that run Story browser gates;
- Story MCP work that reads Story shell/testbed state.

These lessons came from real worker runs and are not obvious from code alone.

## Procedure

1. Treat Story browser gates as worktree-sensitive. Multiple workers often run
   Story tests at the same time.
2. Use a unique `STORY_FEATURE_LOAD_PORT` for each local run or CI job. The
   default `8031` is convenient, but it collides easily across worktrees.
3. Preserve frame/testbed isolation. Story panel `:for` scoping is load-bearing;
   losing it can let panels or feature assertions bleed across contexts.
4. Keep hot-reload baseline setup separate from behavior under test. New-frame
   fingerprint baselines must not trigger hot-reload reruns, or recorder output
   can include fixture events and produce misleading failures.
5. Assert snapshot identities at the browser-visible boundary. JVM hash checks
   are useful, but browser tests should assert the unsigned hex string that the
   shell exposes to users, tools, and downstream visual-regression systems.

## Prompt

Use this block when dispatching Story browser/testbed work:

```text
Before editing Story browser tests or runners, read:
ai/extended-context/story-browser-testbed-invariants.md

Use a unique STORY_FEATURE_LOAD_PORT for local/browser runs in this worktree.
Preserve Story panel :for scoping, avoid fingerprint-baseline setup that causes
hot-reload reruns, and assert browser-visible unsigned snapshot hashes when
testing snapshot identity.
```

## Checks

- Local Story feature-load commands should set an explicit port, for example
  `STORY_FEATURE_LOAD_PORT=8131 npm run test:story-feature-load`.
- Failure output should include the active story/variant/mode and enough DOM or
  URL state to reproduce the issue.
- If a lesson becomes enforceable, put the assertion or comment near the
  runner/test/code as well. This file is orientation, not a substitute for
  local guardrails.
