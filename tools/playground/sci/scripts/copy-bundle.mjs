// Copy the advanced-compiled re-frame2 SCI bundle to its deployed location
// (docs/cljs/playground-rf2.js), keeping shadow's out/ working tree (manifest,
// cljs-runtime) out of the deployed docs/ dir. Run after `shadow-cljs release`.
import { copyFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url)); // tools/playground/sci/scripts
const src = join(here, "..", "out", "playground-rf2.js");
const destDir = join(here, "..", "..", "..", "..", "docs", "cljs");
const dest = join(destDir, "playground-rf2.js");

if (!existsSync(src)) {
  console.error("ERROR: " + src + " not found — run `shadow-cljs release rf2` first.");
  process.exit(1);
}
mkdirSync(destDir, { recursive: true });
copyFileSync(src, dest);
console.log("copied playground-rf2.js -> " + dest);
