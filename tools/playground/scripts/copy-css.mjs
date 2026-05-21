// Copy the playground stylesheet to its deployed location next to the bundle.
// Kept as a tiny copy (not an esbuild CSS bundle) because the stylesheet has no
// @import graph — it is hand-authored and one file. Run as part of `npm run build`.
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url)); // tools/playground/scripts
const src = join(here, "..", "src", "playground.css");
const destDir = join(here, "..", "..", "..", "docs", "cljs");
const dest = join(destDir, "playground.css");

mkdirSync(destDir, { recursive: true });
copyFileSync(src, dest);
console.log("copied playground.css -> " + dest);
