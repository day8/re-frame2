// Cross-platform exec-resolution and filesystem-cleanup safety helpers
// for the mcp-conformance harness. Source: rf2-33vvc.
//
// ## Why this exists
//
// Two accident classes the audit (rf2-33vvc) flagged:
//
//   1. **Windows command-hijack accident** — bare executable names
//      (`npm`, `npx`, `clojure`) invoked with `shell: true` and `cwd`
//      set to a repo-controlled directory resolve against the cwd
//      before PATH. A checkout that happens to carry a `npm.cmd` /
//      `npx.cmd` / `clojure.cmd` in that cwd (e.g. a fixture dir, or
//      anywhere reachable in PATHEXT order) would silently execute it
//      instead of the intended toolchain. The fix is to resolve each
//      tool name to a single trusted absolute path up-front via PATH
//      search, refuse to use any candidate that resolves inside the
//      workspace, and spawn with `shell: false` so the resolved path
//      is the *only* thing the OS gets to interpret.
//
//   2. **Symlink-escape cleanup accident** — `fs.unlinkSync` on a
//      candidate path under a known root will happily delete a file
//      outside that root if any parent component is a symlink. The
//      cleanup step that wipes stale `nrepl.port` files runs before
//      any other validation, so a fixture tree with a symlinked
//      `.shadow-cljs/` could be coerced into deleting an arbitrary
//      file. The fix is to `realpath` the candidate (or, for files
//      that don't exist yet, the candidate's parent + basename) and
//      verify the resolved path stays under the resolved allowed
//      root before unlinking.
//
// Mike's pragmatic security stance (per project policy): trust the
// explicit invoker, gate *accidents* rather than theoretical attacks.
// Both helpers below gate the accident class without adding ceremony
// at the call sites.
//
// ## When NOT to use `resolveTrustedExe`
//
// `resolveTrustedExe` only matters for **system-wide binaries** that
// the OS finds via PATH walk. Two postures sit alongside the helper in
// this artefact (rf2-i3ffz F-COUPLE-1):
//
//   - **`story-mcp` harness** uses `resolveTrustedExe('clojure', ...)`.
//     The JVM binary lives in `/usr/local/bin/clojure` (or wherever
//     `setup-clojure` puts it on CI); the bare-name PATH walk is the
//     accident class the helper gates.
//   - **`re-frame2-pair-mcp` harness** uses `process.execPath`. That's the
//     currently-running Node binary, always an absolute path, always
//     outside the workspace by construction (Node can't have launched
//     from a workspace-internal copy without already trusting it).
//     `cross-spawn`'s `which.sync` short-circuits on the
//     `cmd.match(/\//)` check for absolute paths, so no PATH walk
//     happens. `resolveTrustedExe` would be ceremony with no payoff.
//
// Rule of thumb: if the command is a bare basename (`npm`, `npx`,
// `clojure`), route it through `resolveTrustedExe`. If it's already an
// absolute path on disk (`process.execPath`, a `path.resolve(...)`
// product), pass it straight to `cross-spawn`.
//
// ## API
//
//   resolveTrustedExe(name, { workspaceRoot, env, platform })
//     → absolute path on the host filesystem to an executable named
//       `name`. Walks PATH (and PATHEXT on Windows); returns the
//       first match whose realpath does NOT start with
//       `realpath(workspaceRoot)`. Throws if no candidate is found,
//       or if every candidate resolves inside the workspace.
//
//   safeUnlinkInside(candidatePath, allowedRoot)
//     → unlinks `candidatePath` iff its realpath (or, when the file
//       doesn't exist, the realpath of its parent directory + basename)
//       resolves under `realpath(allowedRoot)`. No-op when the file
//       doesn't exist. Throws on symlink-escape. Returns true if the
//       file existed and was unlinked, false otherwise.
//
// Both helpers are pure-Node, no external deps; they're test-only
// fixtures (bundle-isolated by construction — `tools/mcp-conformance/`
// is not on any production import path).

'use strict';

const fs = require('node:fs');
const path = require('node:path');

// ---------------------------------------------------------------------
// resolveTrustedExe
// ---------------------------------------------------------------------

function isWindows(platform) {
  return (platform || process.platform) === 'win32';
}

function pathExtensionsFor(platform, env) {
  if (!isWindows(platform)) return [''];
  // Windows: PATHEXT controls which extensions are considered
  // executable. Default is `.COM;.EXE;.BAT;.CMD;...`. The empty string
  // is included first so an exact-name match (e.g. `clojure` if the
  // bare file is somehow on disk) also gets considered before the
  // shell-extension fallbacks. Case-insensitive on Windows, but we
  // normalise the candidate filesystem check via lowercased compare
  // below regardless.
  const pathext = env.PATHEXT || '.COM;.EXE;.BAT;.CMD;.VBS;.JS;.WS';
  const exts = pathext.split(';').map((s) => s.trim()).filter(Boolean);
  // Include the bare name (no extension) first — a Unix-style
  // executable on PATH (e.g. a `clojure` shim with no extension) still
  // works on Windows under a POSIX-emulating shell.
  return ['', ...exts];
}

function realpathSyncOrNull(p) {
  try {
    return fs.realpathSync(p);
  } catch {
    return null;
  }
}

// Returns true iff `child` is equal to `parent` or is a descendant of
// it. Both args must already be realpath'd (no symlink in either).
function isPathInside(child, parent) {
  // Normalise trailing separators so `/a/b` doesn't fail to match `/a/b/`.
  const c = path.resolve(child);
  const p = path.resolve(parent);
  if (c === p) return true;
  const sep = path.sep;
  const pWithSep = p.endsWith(sep) ? p : p + sep;
  // Case-insensitive compare on Windows — the filesystem itself is
  // case-insensitive there, and the audit's accident class doesn't
  // care about case.
  if (process.platform === 'win32') {
    return c.toLowerCase().startsWith(pWithSep.toLowerCase());
  }
  return c.startsWith(pWithSep);
}

/**
 * Resolve `name` to an absolute path on disk, scanning `env.PATH`
 * (and, on Windows, `env.PATHEXT`). The returned path MUST realpath
 * to somewhere outside `workspaceRoot` — any candidate that resolves
 * inside the workspace is skipped, and if every candidate fails the
 * check the function throws.
 *
 * @param {string} name  The bare command name (e.g. "npm", "clojure").
 *                       MUST NOT contain a path separator — pass just
 *                       the basename and let PATH search do its job.
 * @param {object} opts
 * @param {string} opts.workspaceRoot
 *                       Absolute path to the repo root. Any candidate
 *                       resolving under this directory is rejected.
 * @param {object} [opts.env]      Environment to read PATH/PATHEXT from
 *                                 (defaults to process.env). Surfaced
 *                                 as a parameter so tests can drive it.
 * @param {string} [opts.platform] Platform string (defaults to
 *                                 process.platform). Surfaced as a
 *                                 parameter so tests can drive both
 *                                 win32 and posix code paths.
 *
 * @returns {string} Absolute path to the resolved executable.
 * @throws  If no candidate is found, if the name contains a path
 *          separator, or if every candidate resolves inside the
 *          workspace.
 */
function resolveTrustedExe(name, opts) {
  if (typeof name !== 'string' || name.length === 0) {
    throw new Error('resolveTrustedExe: name must be a non-empty string');
  }
  if (name.includes('/') || name.includes('\\')) {
    throw new Error(
      'resolveTrustedExe: name must not contain a path separator (got ' +
        JSON.stringify(name) +
        '). Pass the bare basename and let PATH search do its job.',
    );
  }
  const env = opts && opts.env ? opts.env : process.env;
  const platform = opts && opts.platform ? opts.platform : process.platform;
  if (!opts || typeof opts.workspaceRoot !== 'string') {
    throw new Error('resolveTrustedExe: opts.workspaceRoot is required');
  }
  const workspaceRootReal = realpathSyncOrNull(opts.workspaceRoot);
  if (!workspaceRootReal) {
    throw new Error(
      'resolveTrustedExe: workspaceRoot does not exist or is unreadable: ' +
        opts.workspaceRoot,
    );
  }

  const pathStr = env.PATH || env.Path || env.path || '';
  // Per Node docs, path.delimiter is `;` on Windows and `:` on POSIX.
  const dirs = pathStr.split(path.delimiter).filter(Boolean);
  if (dirs.length === 0) {
    throw new Error(
      'resolveTrustedExe: PATH is empty or unset; cannot resolve ' + name,
    );
  }

  const exts = pathExtensionsFor(platform, env);
  const tried = []; // for diagnostic error message
  const rejectedInsideWorkspace = [];

  for (const dir of dirs) {
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      let stat;
      try {
        stat = fs.statSync(candidate);
      } catch {
        continue;
      }
      if (!stat.isFile()) continue;
      tried.push(candidate);
      // Realpath through any symlink — what we trust is the *target*,
      // not the link. A PATH entry pointing at a workspace-internal
      // tool via a symlink is the exact accident the audit flagged.
      const real = realpathSyncOrNull(candidate) || candidate;
      if (isPathInside(real, workspaceRootReal)) {
        rejectedInsideWorkspace.push({ candidate, real });
        continue;
      }
      return real;
    }
  }

  if (rejectedInsideWorkspace.length > 0) {
    const list = rejectedInsideWorkspace
      .map((r) => `  ${r.candidate} -> ${r.real}`)
      .join('\n');
    throw new Error(
      `resolveTrustedExe: every candidate for "${name}" resolved inside ` +
        `the workspace (${workspaceRootReal}). Refusing to execute ` +
        `workspace-relative binaries via PATH (rf2-33vvc accident-gating).\n` +
        `Rejected candidates:\n${list}\n` +
        `Install ${name} system-wide or adjust PATH so a host binary ` +
        `appears first.`,
    );
  }
  throw new Error(
    `resolveTrustedExe: could not find "${name}" on PATH. ` +
      `Tried ${tried.length} file candidate(s) across ${dirs.length} ` +
      `directories (PATHEXT entries: ${JSON.stringify(exts)}).`,
  );
}

// ---------------------------------------------------------------------
// safeUnlinkInside
// ---------------------------------------------------------------------

/**
 * Unlink `candidatePath` only when its realpath resolves under
 * `realpath(allowedRoot)`. Symlink-safe: if any parent component is a
 * symlink whose target escapes the allowed root, the unlink is
 * rejected. No-op when the file doesn't exist.
 *
 * Implementation: when the candidate exists we realpath it directly.
 * When it doesn't exist we realpath its *parent directory* + append
 * the basename and check that — this catches the symlinked-parent
 * case even when the file itself is absent (we still wouldn't want to
 * lstat-then-write through a parent symlink in a future call, but
 * here we just bail before unlink would do damage).
 *
 * @param {string} candidatePath
 * @param {string} allowedRoot
 * @returns {boolean} true if the file existed and was unlinked,
 *                    false otherwise (file didn't exist).
 * @throws  If the candidate's realpath escapes the allowed root.
 */
function safeUnlinkInside(candidatePath, allowedRoot) {
  if (typeof candidatePath !== 'string' || candidatePath.length === 0) {
    throw new Error('safeUnlinkInside: candidatePath must be a non-empty string');
  }
  if (typeof allowedRoot !== 'string' || allowedRoot.length === 0) {
    throw new Error('safeUnlinkInside: allowedRoot must be a non-empty string');
  }
  const allowedRootReal = realpathSyncOrNull(allowedRoot);
  if (!allowedRootReal) {
    throw new Error(
      'safeUnlinkInside: allowedRoot does not exist or is unreadable: ' +
        allowedRoot,
    );
  }

  // lstat first: if the candidate doesn't exist at all, we're done.
  // Using lstat (not stat) so a broken symlink at the leaf is still
  // visible — we want to refuse to unlink even a dangling link if
  // its location is outside the allowed root.
  let leafExists;
  try {
    fs.lstatSync(candidatePath);
    leafExists = true;
  } catch (e) {
    if (e && e.code === 'ENOENT') {
      leafExists = false;
    } else {
      throw e;
    }
  }

  // Resolve a stable absolute path for the candidate. When the leaf
  // exists we can realpath it directly; when it doesn't we realpath
  // the parent directory (which MUST exist for unlink to be meaningful)
  // and re-attach the basename. The parent-realpath path catches a
  // symlinked parent — e.g. `<fixture>/.shadow-cljs` being a symlink
  // to `/etc` — even when the leaf is absent.
  const dir = path.dirname(candidatePath);
  const base = path.basename(candidatePath);
  const parentReal = realpathSyncOrNull(dir);
  let resolvedLeaf;
  if (leafExists) {
    resolvedLeaf = realpathSyncOrNull(candidatePath);
    // If realpath of the existing leaf fails (rare; permissions, race),
    // fall back to parent-realpath + basename so we still get a
    // symlink-resolved path for the containment check.
    if (!resolvedLeaf && parentReal) {
      resolvedLeaf = path.join(parentReal, base);
    }
  } else if (parentReal) {
    resolvedLeaf = path.join(parentReal, base);
  }

  if (!resolvedLeaf) {
    // Parent directory itself doesn't exist; the file definitely
    // doesn't either. Treat as no-op.
    return false;
  }

  if (!isPathInside(resolvedLeaf, allowedRootReal)) {
    throw new Error(
      `safeUnlinkInside: refusing to unlink ${candidatePath} — resolved ` +
        `path ${resolvedLeaf} is outside allowed root ${allowedRootReal} ` +
        `(rf2-33vvc symlink-escape accident-gating).`,
    );
  }

  if (!leafExists) return false;
  fs.unlinkSync(candidatePath);
  return true;
}

module.exports = {
  resolveTrustedExe,
  safeUnlinkInside,
  // Exposed for tests; not part of the public contract.
  _internals: { isPathInside, pathExtensionsFor },
};
