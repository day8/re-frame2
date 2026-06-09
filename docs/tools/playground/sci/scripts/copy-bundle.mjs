// Copy the advanced-compiled re-frame2 SCI bundle to its deployed location
// (docs/cljs/playground-rf2.js), keeping shadow's out/ working tree (manifest,
// cljs-runtime) out of the deployed docs/ dir. Run after `shadow-cljs release`.
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url)); // docs/tools/playground/sci/scripts
const src = join(here, "..", "out", "playground-rf2.js");
const destDir = join(here, "..", "..", "..", "..", "cljs");
const dest = join(destDir, "playground-rf2.js");

// Repo root is five levels up from this script
// (docs/tools/playground/sci/scripts -> repo root).
const repoRoot = resolve(here, "..", "..", "..", "..", "..");

if (!existsSync(src)) {
  console.error("ERROR: " + src + " not found — run `shadow-cljs release rf2` first.");
  process.exit(1);
}

// --- absolute-path normalisation (rf2-6z9clg) -------------------------------
//
// The reg-* macros' source-coord capture bakes the on-disk source path of
// rf2_playground/sci.cljs into the bundle (the always-on error-coord registry
// survives :advanced elision). When shadow-cljs resolves the source root as an
// ABSOLUTE classpath path — e.g. on a worktree checkout — that path is the
// committer's home/worktree path, which trips the "No hardcoded personal/home
// paths" gate (and is meaningless in the deployed artefact). Normalise any
// embedded repo-root prefix to a repo-RELATIVE path so the committed bundle is
// build-CWD-independent and carries no personal/home path. Handles both
// forward-slash (the form CLJS emits) and OS-native (`\` on Windows) prefixes.
function normalizeRepoPaths(text) {
  const variants = new Set([
    repoRoot,
    repoRoot.split(sep).join("/"),
    repoRoot.split("/").join(sep),
  ]);
  let out = text;
  for (const v of variants) {
    // Strip the prefix plus its trailing separator so the residue is a clean
    // repo-relative path (e.g. "docs/tools/playground/sci/src/...").
    out = out.split(v + "/").join("");
    out = out.split(v + sep).join("");
    // Bare prefix with no trailing separator (defensive).
    out = out.split(v).join("");
  }
  return out;
}

const original = readFileSync(src, "utf8");
const normalized = normalizeRepoPaths(original);

mkdirSync(destDir, { recursive: true });
writeFileSync(dest, normalized);
if (normalized !== original) {
  console.log("normalized absolute repo-root paths -> repo-relative in deployed bundle");
}
console.log("copied playground-rf2.js -> " + dest);
