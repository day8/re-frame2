#!/usr/bin/env sh
# scripts/git-hooks/lib/check-stale-mcp-binary.sh
#
# Detection helper for the post-merge stale-MCP-binary warning (rf2-6jj3r).
# Reads a list of newline-separated changed file paths on stdin and prints
# a warning to stderr if any path falls inside an MCP-binary source surface
# whose compiled artefact under `out/` is gitignored and rebuilt locally.
#
# This file is intentionally a pure shell library (no `set -e`, no global
# state mutations) so the Node-side test runner under
# `tools/re-frame2-pair-mcp/test/post-merge-hook-test.cjs` can invoke it
# with synthetic stdin streams and assert against stdout / stderr / exit.
#
# Exit codes:
#   0 — no stale binary; nothing printed.
#   0 — stale binary detected; warning printed to stderr.
#       (Hooks must not block `git pull`; we are advisory only.)
#
# Stdout: never used.
# Stderr: warning block on detection.
#
# Cross-platform: POSIX sh; runs under Git Bash on Windows, macOS, Linux.
# No bashisms (`[[`, arrays, `<<<`). Tested under `sh`, `bash`, `dash`.

# Each MCP surface is declared by a label + a path-prefix gate. A change
# to ANY file whose path starts with the prefix means the binary is now
# stale and the operator must rebuild + bounce the MCP server.
#
# Adding a new MCP surface = add one stanza to `mcp_surfaces`.

mcp_surfaces() {
  # label|prefix|rebuild-cmd|bounce-hint
  printf '%s\n' \
    're-frame2-pair-mcp|tools/re-frame2-pair-mcp/src/|npm --prefix tools/re-frame2-pair-mcp run build|Restart Claude Code (or your MCP host) to bounce the server.' \
    're-frame2-pair-mcp|tools/re-frame2-pair-mcp/shadow-cljs.edn|npm --prefix tools/re-frame2-pair-mcp run build|Restart Claude Code (or your MCP host) to bounce the server.' \
    're-frame2-pair-mcp|tools/re-frame2-pair-mcp/deps.edn|npm --prefix tools/re-frame2-pair-mcp run build|Restart Claude Code (or your MCP host) to bounce the server.' \
    're-frame2-pair-mcp|tools/re-frame2-pair-mcp/package.json|npm --prefix tools/re-frame2-pair-mcp run build|Restart Claude Code (or your MCP host) to bounce the server.'
}

# check_stale_mcp_binary
#
# Reads newline-separated file paths from stdin. For each MCP surface,
# collects matching paths; if any matched, emits a single warning block
# per surface to stderr.
check_stale_mcp_binary() {
  changed_paths=$(cat)
  if [ -z "$changed_paths" ]; then
    return 0
  fi

  any_match=0
  # iterate surfaces; aggregate per label so multiple surface stanzas
  # sharing a label collapse into one warning block.
  seen_labels=""

  mcp_surfaces | while IFS='|' read -r label prefix cmd bounce; do
    matches=$(printf '%s\n' "$changed_paths" | grep -E "^${prefix}" || true)
    if [ -z "$matches" ]; then
      continue
    fi

    # de-dupe by label across surface stanzas
    case " $seen_labels " in
      *" $label "*)
        ;;
      *)
        seen_labels="$seen_labels $label"
        printf '\n' >&2
        printf '====================================================================\n' >&2
        printf '  %s: source changed — local binary is now stale (rf2-6jj3r)\n' "$label" >&2
        printf '====================================================================\n' >&2
        printf '\n' >&2
        printf '  The compiled MCP binary under tools/re-frame2-pair-mcp/out/ is\n' >&2
        printf '  gitignored and rebuilt locally. The just-pulled commits include\n' >&2
        printf '  source-side changes that the running MCP server has NOT picked up.\n' >&2
        printf '\n' >&2
        printf '  Changed files in this pull:\n' >&2
        ;;
    esac

    # print the matched paths under this stanza (indented)
    printf '%s\n' "$matches" | while IFS= read -r p; do
      [ -n "$p" ] && printf '    %s\n' "$p" >&2
    done

    any_match=1
  done

  # NB: the `while … done` ran in a subshell under POSIX sh, so any_match
  # there is lost. Re-derive from a single match probe at the end.
  any=$(mcp_surfaces | while IFS='|' read -r label prefix cmd bounce; do
    printf '%s\n' "$changed_paths" | grep -E "^${prefix}" >/dev/null 2>&1 && echo y
  done | head -n 1)

  if [ "$any" = "y" ]; then
    # print one shared remediation footer (commands are identical across
    # current stanzas; keep it tight)
    printf '\n' >&2
    printf '  To refresh:\n' >&2
    printf '    1.  npm --prefix tools/re-frame2-pair-mcp run build\n' >&2
    printf '    2.  Restart Claude Code (or your MCP host) so the bounced\n' >&2
    printf '        binary is exec-ed on the next tool call.\n' >&2
    printf '\n' >&2
    printf '  Why this warning exists: tools/re-frame2-pair-mcp/out/ is\n' >&2
    printf '  intentionally gitignored (build artefacts rebuild per-environment).\n' >&2
    printf '  Without this hook, pulled MCP fixes are silently inert until rebuild.\n' >&2
    printf '====================================================================\n' >&2
    printf '\n' >&2
  fi

  return 0
}
