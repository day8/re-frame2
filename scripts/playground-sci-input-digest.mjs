#!/usr/bin/env node
// scripts/playground-sci-input-digest.mjs
//
// Deterministic input digest for the generated playground SCI bundle
// (docs/cljs/playground-rf2.js) — build PROVENANCE (rf2-i3e3q, rescoped by
// rf2-tzy13).
//
// STATUS. This digest is no longer a freshness AUTHORITY. rf2-tzy13 untracked
// the bundle: it is .gitignored and generated at each consumption boundary
// (test.yml's tools-playground job at PR time, docs.yml on deploy), so there is
// no committed snapshot that can lag its source and nothing to verify a marker
// against. The former verifier, scripts/check-playground-sci-freshness.sh, was
// deleted with the committed artefact. What survives — and why this module is
// still here — is provenance: copy-bundle.mjs stamps the digest into the file it
// emits, so any generated bundle records the exact input set it was compiled
// from. That is diagnostic (answering "which tree produced this artefact?" for a
// downloaded or deployed copy), not gating.
//
// The bundle is a shadow-cljs :advanced (Closure) build whose minified-symbol
// allocation is NOT cross-machine reproducible, so it cannot be byte-diffed
// against a fresh rebuild. What IS stable across machines is the SET OF SOURCE
// INPUTS the bundle is compiled from. This module hashes exactly that input
// roster into one 64-hex digest:
//
//   digest = sha256( sorted "<git-blob-sha>  <repo-relative-path>" lines )
//
// where each blob sha is `git hash-object` of the WORKING-TREE file (git applies
// the path's clean filter — EOL normalisation — so the sha is identical on a
// Windows checkout and a Linux CI runner). The digest therefore changes iff any
// declared baked-in input changes, and does NOT change when Closure reshuffles
// minified identifiers. It has one consumer:
//
//   docs/tools/playground/sci/scripts/copy-bundle.mjs appends the digest as an
//   unminified `//# rf2-sci-input-digest=<hex>` marker to the bundle it emits,
//   so a generated bundle records the inputs it was built from.
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
//
// WHY HARDCODED, AND THE BOUND (rf2-nyjml). This list is declared, not derived.
// Deriving it truthfully would mean resolving the CLJS `:require` graph across
// the shadow-cljs build plus the deps.edn classpath — a general build-graph
// analyser, which this gate is explicitly scoped NOT to grow. The declaration is
// held honest from two sides instead:
//   1. computeInputDigest below fails if ANY entry matches no tracked file, so a
//      renamed or moved tree REDS rather than silently leaving the digest.
//   2. implementation/scripts/_playground-sci-inputs.test.cjs derives its checks
//      FROM this roster (never a second hardcoded copy) and proves every entry's
//      tracked files select the `playground` changed-surface, so the digest's
//      declared inputs and the job that rebuilds the bundle cannot drift apart.
// What neither can catch is an input class that is baked in but was never
// declared here at all. That residual bound is the reason each entry carries the
// comment saying WHY it is baked in: adding a new artefact to the bundle's
// require graph means adding it here in the same change.
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
  // --- postprocess: copy-bundle.mjs is the last writer of the emitted artefact
  // (rf2-nyjml). It rewrites absolute repo-root paths to repo-relative and
  // appends the digest marker itself, so its content CHANGES THE BYTES of
  // docs/cljs/playground-rf2.js. Omitted, a copy-bundle edit could alter every
  // emitted bundle while the provenance marker claimed an unchanged input set —
  // the marker would be attesting to a tree that no longer describes the file.
  "docs/tools/playground/sci/scripts",
  // --- the digest algorithm itself (rf2-nyjml). Self-referential but not
  // circular: this is a `git hash-object` of the file's own blob, which
  // terminates. A digest is a claim about WHICH inputs were hashed and HOW;
  // changing the roster or the hashing changes what the marker MEANS, so two
  // bundles built from identical sources under different algorithms must not
  // carry the same digest.
  "scripts/playground-sci-input-digest.mjs",
];

function git(args, opts = {}) {
  return execFileSync("git", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...opts });
}

// The 64-hex input digest of the current working-tree roster. Throws (loudly,
// non-zero on the CLI) if git is unavailable or any roster entry matched no
// tracked files (a misconfigured roster must fail the gate, never silently pass).
export function computeInputDigest() {
  const root = git(["rev-parse", "--show-toplevel"]).trim();

  // Expand PER ENTRY, never as one combined pathspec (rf2-nyjml). One
  // `git ls-files -- <all entries>` reports only the UNION, which hides
  // per-entry drift: rename one baked-in tree and the surviving entries still
  // return ~all of the files, so a whole-roster emptiness check stays silent
  // while that input class has silently left the digest — the bundle could then
  // change with no digest movement, which is the precise failure this module
  // exists to make impossible. Measured before the fix: renaming
  // implementation/flows/src left 140 of 144 files and the guard did not fire.
  const seen = new Set();
  const emptyEntries = [];
  for (const entry of ROSTER) {
    const matched = git(["ls-files", "-z", "--", entry], { cwd: root })
      .split("\0")
      .filter(Boolean);
    if (matched.length === 0) emptyEntries.push(entry);
    for (const file of matched) seen.add(file);
  }
  const files = [...seen].sort();
  if (emptyEntries.length > 0 || files.length === 0) {
    throw new Error(
      "playground-sci-input-digest: roster matched no tracked files — the input " +
        "paths drifted. Update ROSTER in scripts/playground-sci-input-digest.mjs." +
        (emptyEntries.length > 0
          ? ` Entries matching nothing: ${emptyEntries.join(", ")}.`
          : ""),
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
