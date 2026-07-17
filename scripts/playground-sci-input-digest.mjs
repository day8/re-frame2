#!/usr/bin/env node
// scripts/playground-sci-input-digest.mjs
//
// Deterministic input digest for the committed playground SCI bundle
// (docs/cljs/playground-rf2.js) — the mechanically-honest freshness contract
// (rf2-i3e3q).
//
// The committed bundle is a shadow-cljs :advanced (Closure) build whose
// minified-symbol allocation is NOT cross-machine reproducible, so it cannot be
// byte-diffed against a fresh rebuild. What IS stable across machines is the SET
// OF SOURCE INPUTS the bundle is compiled from. This module hashes exactly that
// input roster into one 64-hex digest:
//
//   digest = sha256( sorted "<git-blob-sha>  <repo-relative-path>" lines )
//
// where each blob sha is `git hash-object` of the WORKING-TREE file (git applies
// the path's clean filter — EOL normalisation — so the sha is identical on a
// Windows checkout and a Linux CI runner). The digest therefore changes iff any
// declared baked-in input changes, and does NOT change when Closure reshuffles
// minified identifiers. It is computed here (Node) and consumed in two places
// that MUST agree byte-for-byte, so both invoke THIS one module:
//
//   1. docs/tools/playground/sci/scripts/copy-bundle.mjs appends the digest as
//      an unminified `//# rf2-sci-input-digest=<hex>` marker to the bundle it
//      emits, so the COMMITTED bundle records the inputs it was built from.
//   2. scripts/check-playground-sci-freshness.sh recomputes this digest from the
//      current inputs and compares it to the marker embedded in the committed
//      bundle. Mismatch (or missing marker) => the committed bundle is stale vs
//      its source and the gate fails until it is rebuilt + recommitted.
//
// CLI: prints the digest to stdout.
//   node scripts/playground-sci-input-digest.mjs
// Programmatic: `import { computeInputDigest, ROSTER } from ".../playground-sci-input-digest.mjs"`.

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { pathToFileURL } from "node:url";

// The EXACT tracked source/config/lock inputs the shadow-cljs :advanced SCI
// bundle bakes in, grouped by input class so the roster is auditable and each
// class can be mutation-tested. git pathspecs, repo-root-relative. A directory
// pathspec expands (via `git ls-files`) to every tracked file under it, so a new
// source file in any of these trees is automatically in the roster.
export const ROSTER = [
  // --- core: re-frame.core / router / subs / registrar / views + adapter iface
  "implementation/core/src",
  "implementation/core/deps.edn",
  // --- reagent-slim: reagent2.* + re-frame.adapter.reagent-slim (the substrate)
  "implementation/adapters/reagent-slim/src",
  "implementation/adapters/reagent-slim/deps.edn",
  // --- machines: re-frame.machines (Spec 005), :require'd at bundle init
  "implementation/machines/src",
  "implementation/machines/deps.edn",
  // --- flows: re-frame.flows (Spec 007), :require'd at bundle init
  "implementation/flows/src",
  "implementation/flows/deps.edn",
  // --- the SCI bundle source itself
  "docs/tools/playground/sci/src",
  // --- SCI / shadow-cljs build configuration
  "docs/tools/playground/sci/shadow-cljs.edn",
  "docs/tools/playground/sci/deps.edn",
  // --- dependency lock: react/react-dom/shadow-cljs pins bundled into the file
  "docs/tools/playground/sci/package.json",
  "docs/tools/playground/sci/package-lock.json",
];

function git(args, opts = {}) {
  return execFileSync("git", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...opts });
}

// The 64-hex input digest of the current working-tree roster. Throws (loudly,
// non-zero on the CLI) if git is unavailable or the roster matched no tracked
// files (a misconfigured roster must fail the gate, never silently pass).
export function computeInputDigest() {
  const root = git(["rev-parse", "--show-toplevel"]).trim();
  const listed = git(["ls-files", "-z", "--", ...ROSTER], { cwd: root });
  const files = listed.split("\0").filter(Boolean).sort();
  if (files.length === 0) {
    throw new Error(
      "playground-sci-input-digest: roster matched no tracked files — the input " +
        "paths drifted. Update ROSTER in scripts/playground-sci-input-digest.mjs.",
    );
  }
  // One blob sha per file, in the SAME order as the input paths.
  const shas = git(["hash-object", "--stdin-paths"], {
    cwd: root,
    input: files.join("\n") + "\n",
  })
    .trim()
    .split("\n");
  if (shas.length !== files.length) {
    throw new Error(
      `playground-sci-input-digest: hashed ${shas.length} objects for ${files.length} files.`,
    );
  }
  const manifest = files.map((f, i) => `${shas[i]}  ${f}`).join("\n") + "\n";
  return createHash("sha256").update(manifest, "utf8").digest("hex");
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  process.stdout.write(computeInputDigest() + "\n");
}
