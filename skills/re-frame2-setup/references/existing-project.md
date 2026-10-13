# Existing CLJS project — convert, then merge

The second in-scope starting point: a CLJS project with shadow-cljs / Clojure already present but zero re-frame2 wiring. The [canonical greenfield path](../SKILL.md#canonical-greenfield-path-six-steps) still runs end to end. At step 1, convert a project whose classpath lives in `shadow-cljs.edn`, merge into the files the project already has, and write the ones it lacks as they stand. The leaf's shapes in [`first-counter.md`](first-counter.md) are the *target*, not a literal overwrite. A project with substantial app code or other state management is not greenfield — route it to `re-frame2`.

## A shadow-only project has no `deps.edn` — convert it first

Establish which file owns the classpath before adding a coordinate anywhere: a project described as "`shadow-cljs.edn` exists" ordinarily has no `deps.edn` at all. Such a project resolves its classpath from `shadow-cljs.edn`'s own `:dependencies [[...]]` vector, which shadow feeds to its Maven resolver — Maven coordinates only, so it can express neither `:local/root` nor `:git/url`. Those are exactly the two shapes that work before re-frame2 is on Clojars ([`SKILL.md`](../SKILL.md) step 2), so on this input there is no coordinate to add: writing `[day8/re-frame2 "<VERSION>"]` into `:dependencies` 404s at resolution. Convert instead, in one edit:

1. **Write a `deps.edn`.** `:paths` carries the author's existing app source dirs; `:deps` carries whatever was in `:dependencies` plus the day-one coords; a `:shadow` alias carries `thheller/shadow-cljs` at the version the project already uses, with the existing test dirs as its `:extra-paths`; and the leaf's `:dev` alias comes across as it is.
2. **Point `shadow-cljs.edn` at it.** Replace `:dependencies` with `{:deps {:aliases [:shadow :dev]}}`, keeping the author's `:builds`.
3. **Move every source dir in the same edit.** Once the classpath comes from `deps.edn`, shadow warns about and ignores `shadow-cljs.edn`'s `:source-paths`, so any dir left only there drops off the classpath. The leaf's split is the model: app dirs on `deps.edn` `:paths`, test dirs on the `:shadow` alias's `:extra-paths`.

Post-publish the Maven-only `:dependencies` form becomes workable again and the conversion is unnecessary; it is the pre-publish arm of the same publication-state branch as step 2.

## Merge into the files that exist

- **`deps.edn`** — add the two `day8/re-frame2*` coords, the view-library pin, and the `:shadow` and `:dev` aliases to the existing file; don't replace it.
- **`package.json`** — add the leaf's npm deps to the existing file.
- **`shadow-cljs.edn`** — fold in the `:app` build, and the `:test` build if you keep the starter test. Keep the author's other `:builds`, mount-point id, dev-http port, and `:output-dir` / `:asset-path` if set.

Then continue at `SKILL.md` step 2 with the merged project.
