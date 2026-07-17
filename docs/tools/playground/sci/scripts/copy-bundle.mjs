// Copy the advanced-compiled re-frame2 SCI bundle to its deployed location
// (docs/cljs/playground-rf2.js), keeping shadow's out/ working tree (manifest,
// cljs-runtime) out of the deployed docs/ dir. Run after `shadow-cljs release`.
import { execFileSync } from "node:child_process";
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

// --- input-digest freshness marker (rf2-i3e3q) ------------------------------
//
// Append a deterministic, unminified digest of the exact source/config/lock
// inputs this bundle was compiled from. `scripts/check-playground-sci-freshness.sh`
// recomputes the same digest from the current inputs and compares it to this
// marker, so a committed bundle that has drifted out of sync with its source
// (an input changed without a rebuild) FAILS the gate. The digest is over the
// INPUTS, not the Closure output, so it is cross-machine stable (a Windows and a
// Linux rebuild of the same source embed the same marker even though their
// minified identifiers differ). Computed via the ONE shared module the gate also
// runs, so both sides agree byte-for-byte. Appended AFTER path normalisation so
// the marker (hex only) is never rewritten.
const digestScript = join(repoRoot, "scripts", "playground-sci-input-digest.mjs");
const inputDigest = execFileSync("node", [digestScript], {
  cwd: repoRoot,
  encoding: "utf8",
}).trim();
if (!/^[0-9a-f]{64}$/.test(inputDigest)) {
  console.error("ERROR: input digest is not a 64-hex string: " + JSON.stringify(inputDigest));
  process.exit(1);
}
const withMarker =
  normalized.replace(/\n*$/, "\n") + "//# rf2-sci-input-digest=" + inputDigest + "\n";

mkdirSync(destDir, { recursive: true });
writeFileSync(dest, withMarker);
if (normalized !== original) {
  console.log("normalized absolute repo-root paths -> repo-relative in deployed bundle");
}
console.log("stamped rf2-sci-input-digest=" + inputDigest);
console.log("copied playground-rf2.js -> " + dest);
