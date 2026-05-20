# Template — Repo Split (Monorepo → External Repo)

> **Normative procedure for moving `tools/template/` out of the
> re-frame2 monorepo to its permanent home at
> `github.com/day8/re-frame2-template`** (rf2-dolpf §4 / rf2-7jgkv).
>
> Scope: this doc owns the migration sequence + the deps-new coord
> retarget. The steady-state spec of the template lives in 000 / 001
> / 002 / API / Principles / DESIGN-RATIONALE; this doc retires once
> the external repo is live and the in-monorepo `tools/template/` is
> stubbed or deleted.

## §1 Why split

The template's published invocation surface is `clojure -Tnew create
:template io.github.day8/re-frame2-template`. The `io.github.*`
prefix triggers deps-new's auto-git-clone path
(`tools.deps.extensions.git`'s `auto-git-url`) — deps-new clones the
named GitHub repo and resolves the template body inside the cloned
tree. For that to work end-to-end, **the template body must live at
the root of a standalone GitHub repo**, not nested under
`tools/template/` in a monorepo. A monorepo-nested template can be
exercised via `:local/root` for development, but cannot be the
published production-invocation target — deps-new clones the named
repo, not a subdirectory of it.

Secondary benefits:

- **Release cadence independence.** Template releases tag on the
  template repo, not the monorepo. The two trees ship at their own
  pace. The `tools/template/VERSION` file (added at rf2-h0w5y §3.1)
  is already independent of the framework-wide repo-root `VERSION`,
  in anticipation.
- **Smaller clone footprint** for `clojure -Tnew` consumers — they
  get the template's own commits, not the full re-frame2 monorepo
  (which is heavy: 22 K-line spec, full implementation tree, multiple
  tool surfaces, etc.).
- **Cleaner CI surface.** The template repo owns its
  `template-release.yml` workflow + tag-on-release pipeline
  (rf2-h0w5y §3.1); the monorepo's CI doesn't need to know about
  template publishes post-split.

## §2 What lives where after the split

```
github.com/day8/re-frame2-template/        ; external repo (NEW)
├── README.md                              ; invocation + quick start (copy of tools/template/README.md)
├── deps.edn                               ; the template's own deps (test-time only)
├── VERSION                                ; the template's own version (e.g. 0.0.1.alpha)
├── template.edn                           ; deps-new declarative config (placeholder)
├── src/day8/re_frame2_template/
│   └── hooks.clj                          ; :data-fn / :template-fn / :post-process-fn
├── resources/day8/re_frame2_template/
│   ├── template.edn                       ; the in-tree template config (resource-side)
│   ├── root/                              ; bulk-copied content (README, lefthook, dev/, resources/public/)
│   ├── _shared/                           ; renamed content (dotfiles, src/test sources)
│   ├── _reagent/                          ; Reagent-specific
│   ├── _uix/                              ; UIx-specific
│   └── _helix/                            ; Helix-specific
├── spec/                                  ; the spec/ tree (000-Vision, 001-Substrate-Variants, ...)
├── test/day8/re_frame2_template/
│   ├── template_test.clj
│   ├── template_emission_test.clj
│   └── emitted_test_run_test.clj
└── .github/workflows/
    └── template-release.yml               ; tag-on-release CI (moved from re-frame2 monorepo)

github.com/day8/re-frame2/                 ; monorepo (current)
└── tools/template/                        ; STUB or DELETED after the split (see §6)
```

The on-disk path inside the external repo is **identical** to the
in-monorepo path under `tools/template/`. deps-new's `find-root`
resolves `:template io.github.day8/re-frame2-template` against the
cloned external repo's `resources/day8/re_frame2_template/`
directory; the on-disk shape doesn't need to change.

## §3 Migration sequence (operator-side)

This is the **operator-handoff sequence**. The scaffolding (this
doc, the migration note, the cross-ref sweep) landed under
rf2-7jgkv; the steps below are Mike-side actions that complete the
split.

### §3.1 Seed the external repo

Use `git subtree split` to extract `tools/template/`'s history from
the monorepo into a new branch:

```bash
# In the re-frame2 monorepo
cd /path/to/re-frame2
git subtree split --prefix=tools/template -b template-split-export
```

This produces a `template-split-export` branch whose history is the
subset of monorepo commits that touched `tools/template/`, rewritten
to look like a standalone repo (`tools/template/` becomes the new
repo root).

Then create the external repo and push:

```bash
# Create the new repo on github.com/day8/re-frame2-template (empty)
# via the GitHub web UI or gh repo create.

# Push the extracted history
git remote add template-repo git@github.com:day8/re-frame2-template.git
git push template-repo template-split-export:main
```

### §3.2 Move the release workflow

The template-release workflow (`.github/workflows/template-release.yml`,
landed at rf2-h0w5y §3.1) currently lives in the re-frame2 monorepo.
Move it to the external repo:

```bash
# In the external repo
cp /path/to/re-frame2/.github/workflows/template-release.yml \
   .github/workflows/template-release.yml
git add .github/workflows/template-release.yml
git commit -m "ci: tag-on-release workflow (moved from monorepo)"
git push template-repo main
```

The workflow's checkout step + path references (`tools/template/...`)
need adjusting against the external repo's root layout (the template
now lives at the repo root, not under `tools/template/`). Concrete
edits:

- Drop the `tools/template/` prefix from path references:
  `tools/template/VERSION` → `VERSION`,
  `tools/template/test/` → `test/`, etc.
- The `clojure -M:test` invocation runs from the repo root.
- The `paths:` filter on the `push:` trigger drops the
  `tools/template/**` prefix (every change in the external repo
  touches the template).

### §3.3 Pin the initial release tag

The git-coord distribution (rf2-h0w5y §3.1) cuts a tag per release.
The first tag on the external repo establishes the initial release
that `clojure -Tnew create :template io.github.day8/re-frame2-template`
can resolve:

```bash
# In the external repo (or via gh release create)
cd /path/to/external-repo
git tag template-v0.0.1.alpha
git push template-repo template-v0.0.1.alpha
```

(The `template-v…` prefix matches the workflow's trigger filter; see
rf2-h0w5y §3.1.)

### §3.4 Verify the published invocation works

From any directory (not inside either repo):

```bash
cd $(mktemp -d)
clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app
cd my-app
cat deps.edn  # should reference re-frame2 alpha coords
```

If deps-new resolves the template via the git-coord and the
generated tree matches the in-monorepo template's emit set, the
external repo is live.

### §3.5 Retire the in-monorepo `tools/template/`

Once §3.4 is green, the in-monorepo `tools/template/` becomes
redundant. Two options:

**Option A — Stub.** Replace `tools/template/` with a one-file
README pointing at the external repo. Cheap and discoverable for
anyone who lands at the old path. Keep enough of the original
README to redirect:

```markdown
# tools/template/ — moved

The re-frame2 template moved to its own repo:
**[github.com/day8/re-frame2-template](https://github.com/day8/re-frame2-template)**.

Invocation:

    clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app

See the external repo for the full spec, source, and CI.
```

**Option B — Delete.** Remove `tools/template/` entirely; rely on
the repo-root README's "Project layout" entry and this doc's history
to point readers at the external repo.

The bead reviewing this decision should weigh discoverability (A is
friendlier for anyone who clones the monorepo expecting the template
there) against repo hygiene (B is cleaner; the template's history
already migrated). File a follow-on bead for the stub-vs-delete
call.

### §3.6 Sweep remaining monorepo cross-refs

After §3.5, sweep the monorepo for any remaining `tools/template/`
references that survived the docs sweep and either:

- Point at the external repo (`https://github.com/day8/re-frame2-template`).
- Delete if the reference is now meaningless.

`tools/deps.edn`'s `:local/root "template"` entry retires (the
testbed / cross-tool tests no longer link the template; the
template's tests run in its own CI on the external repo). The
`tools/shadow-cljs.edn` "JVM-only" exclusion comment retires —
the template is no longer part of the tools/ classpath at all.

The repo-root README's Project Layout entry retires (replace the
`template/` row with a one-line "see github.com/day8/re-frame2-template"
note, or drop entirely).

## §4 Deps-new coord retarget

The deps-new coord used to invoke the template changes shape across
the split:

| Phase | Coord | Resolution |
|---|---|---|
| Pre-split (development) | `day8/re-frame2-template` | `:local/root "tools/template"` in the consuming `deps.edn`. Used by tests + manual smoke. Does NOT trigger deps-new's auto-git-clone. |
| Pre-split (via published-shape coord) | `io.github.day8/re-frame2-template` | deps-new clones the **re-frame2 monorepo** (because the `day8/re-frame2-template` GitHub repo doesn't exist yet), then fails to find `resources/day8/re_frame2_template/template.edn` at the cloned repo root (it's under `tools/template/` in the monorepo). Not a viable production path; only `:local/root` works pre-split. |
| Post-split (published) | `io.github.day8/re-frame2-template` | deps-new clones from `https://github.com/day8/re-frame2-template.git` via `auto-git-url`, then resolves the template body inside the cloned tree at `resources/day8/re_frame2_template/`. |
| Post-split (pinned to tag) | `io.github.day8/re-frame2-template#template-v<version>` | Same as above, but pinned to a specific git tag (matches the `template-v…` tag space from rf2-h0w5y §3.1). |

The local-dev fallback (`:local/root` against a checkout of the
external repo) continues to work post-split:

```bash
git clone https://github.com/day8/re-frame2-template.git
cd /path/to/consumer
clojure -Sdeps '{:deps {day8/re-frame2-template
                        {:local/root "/path/to/re-frame2-template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app
```

(Note: `day8/re-frame2-template`, not `io.github.day8/re-frame2-template`,
for the `:local/root` path — the `io.github.*` prefix triggers
auto-clone before classpath lookup, which loses the local override.)

## §5 CI workflow (lives in the external repo post-split)

The `template-release.yml` workflow (from rf2-h0w5y §3.1) currently
lives in the re-frame2 monorepo at
`.github/workflows/template-release.yml`. Post-split it lives **in
the external repo**, not the monorepo.

Shape (unchanged across the split, modulo path adjustments per §3.2):

- Trigger: push of a tag matching
  `template-v[0-9]+.[0-9]+.[0-9]+*`.
- Steps:
  1. `clojure -M:test` — runs the template's own JVM test suite.
  2. Read the `VERSION` file.
  3. Verify the tag matches `template-v<VERSION>`.
  4. Cut a GitHub Release pointing at the tagged commit.
- Secrets needed: `GITHUB_TOKEN` (default; for push-tag permission).
  **No Clojars credentials** — git-coord distribution has no
  artefact-upload step.

The monorepo's CI doesn't carry any template-publish hooks post-split;
the template owns its own release cadence end-to-end.

## §6 Decision: stub vs. delete the in-monorepo tools/template/

Open question for the operator handoff. See §3.5 above. File a
follow-on bead once the external repo is live; resolve the question
based on whether the monorepo discoverability gain (Option A) or
the repo-hygiene gain (Option B) wins.

Pre-alpha posture: hard rename, no transitional carry-over of the
template source. The template's history lives in the external repo
post-split; the monorepo carries a pointer at most.

## §7 Cross-references

- [003-DepsNew-Rebuild-Plan.md](003-DepsNew-Rebuild-Plan.md) §4 —
  the parent migration plan; this doc owns §4.3.
- [`tools/template/README.md`](../README.md) — the template's
  user-facing README, updated to reference the future external
  location.
- [`tools/template/spec/000-Vision.md`](000-Vision.md) — Lineage
  section enumerates the Clojars → git-coord shift.
- [`tools/template/spec/DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
  §1 — WHY deps-new + git-coord over clj-new + Clojars.
- [`migration/from-clj-new-template/README.md`](../../../migration/from-clj-new-template/README.md)
  — user-facing migration note for existing clj-new template users.
- rf2-dolpf — EPIC umbrella; closes once §4.3 operator handoff lands.
- rf2-7jgkv — this scaffolding + cross-ref sweep.
- rf2-h0w5y — git-coord release pipeline (the
  `template-release.yml` workflow, which moves into the external
  repo as part of the split).
