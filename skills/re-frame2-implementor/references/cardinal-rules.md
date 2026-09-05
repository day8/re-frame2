# Cardinal rules — re-frame2 implementor

These hold across every phase of building a new re-frame2 implementation. Each rule expands the one-line form carried in `SKILL.md`.

---

## 1. The spec is the contract

[`spec/`](https://github.com/day8/re-frame2/tree/main/spec) is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; draft a GitHub issue against `day8/re-frame2` and ask the engineer to OK it before filing — see rule 9.

**Pin the spec before reading it.** Phase 1 records a specific `day8/re-frame2` commit/tag in the port profile ([`phase-1-decisions.md`](phase-1-decisions.md)). Before reading the spec corpus, verify `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin **and** that the origin is `https://github.com/day8/re-frame2`. An unpinned or unverified checkout is not a contract — it's whatever happens to be on the filesystem.

**Resolve every contract read through that verified checkout.** Each `spec/…` owner this skill names — the EP-index rows, the fixed-obligation links, the conformance contract — is read from `<path-to-re-frame2>/spec/<file>` at the recorded pin. The `https://day8.github.io/re-frame2/spec/…` links beside them are **citations** for human browsing: they render live main, which can sit ahead of (or behind) your pin, so they are never the reading route. A session that reads the live site can implement a different contract than the one the profile pinned.

## 2. Phase 1 before Phase 2

The profile's choices (host, mechanisms, scope, capability claim) are load-bearing for every line of Phase 2 code. Record them before implementing. If a Phase 2 slice forces a choice to change, stop the slice, revise the profile line, resume. Recording means writing the profile down — committing it is a currentness concern for the done gate ("committed and current" in `SKILL.md` §Done), never a gate before code.

## 3. Implement in dependency order

EP 001 (Registration) → 002 (Frames + events + effects + subs) → 006 (Reactive substrate) → views (the render binding — no numbered Spec; Implementor-Checklist §V1 + Spec 002 §frame-root) → 009 (Instrumentation) → 015 (Data Classification) → 013 (Flows) are the foundation; acceptance gate 1 — the **required-foundation gate**, every fixture applicable to the four v1-required families (`:core/*` + `:identity/*` + `:flow/*` + `:data-classification/*`, tags derived from the corpus per [`conformance.md` §Capability tagging](conformance.md#capability-tagging)) — sits at the end of the cluster. 009 is in the foundation because `:core/trace` and `:core/error` fixtures exercise it; 015 follows immediately because it is **v1-required** ([`spec/015-Data-Classification.md`](https://day8.github.io/re-frame2/spec/015-Data-Classification/)) and overlays the 009 emission boundary — ship the gate without it and classified values leak through every observation surface; 013 closes the cluster because it too is **v1-required** ([`spec/013-Flows.md`](https://day8.github.io/re-frame2/spec/013-Flows/)) and stands on all six steps before it. Optional EPs — the checklist's Q1–Q9 — sit downstream, per the profile's claim.

## 4. Substrate-agnostic phrasing in code and docs

The reference impl is a CLJS+Reagent realisation — keywords as ids, hiccup as render-tree, Reagent ratoms as the reactive container. These are *choices the reference made*. Write to "the identity primitive", "the render-tree", "the reactive container" — not to hiccup / Reagent / keywords.

## 5. No core.async equivalents

Every in-scope host cross-compiles to JS, so async effects schedule via the JS event loop's primitives (Promise / setTimeout / queueMicrotask); cross-frame work is serialised per frame via the run-to-completion drain. Channel-shaped concurrency may exist *internally*, but the public dispatch contract is the drain.

## 6. JVM-runnability for the testing surface

Where the CLJS reference makes `compute-sub` and `machine-transition` pure-function-callable from JVM, your port's analogue must be callable from a non-substrate test harness. Pure transitions and pure sub computations are the bedrock of the test story.

## 7. Conformance corpus is the acceptance test

[`spec/conformance/`](https://github.com/day8/re-frame2/tree/main/spec/conformance) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of the claim; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — see rule 8.

**The claim is not a dial for making the score look better.** The four v1-required families (`:core/*`, `:identity/*`, `:flow/*`, `:data-classification/*`) are always claimed, so `claimed-applicable` can never be shrunk by declining one — the harness refuses a `known-skipped` entry naming a required capability. `passed / claimed-applicable` is only an honest number while the denominator is the whole required surface plus whatever was genuinely claimed on top.

## 8. If you find a spec gap, draft a GitHub issue and ask before filing. Do not paper.

When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. **Draft** a GitHub issue against `day8/re-frame2`, show the engineer the title + body, and **ask for explicit OK before running `gh issue create`** (rule 9). Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

`bd` (beads) is the re-frame2 monorepo's internal tracker and is **never invoked from a published skill**. The implementor skill runs in the engineer's port repo; spec gaps reach the framework maintainers via the upstream repo's GitHub issues — and only after the engineer has OK'd the specific issue body.

**Search before filing — reference an existing issue instead of duplicating.** A spec gap you hit (an ambiguous fixture, a missing surface) may already be tracked upstream. Before drafting, list `day8/re-frame2`'s existing issues for the same gap and, if one matches, reference it instead of filing a duplicate — show the engineer the existing issue and stop:

```bash
gh issue list --repo day8/re-frame2 --search "<agent-authored safe keywords>"
```

**`--search` is an inline shell argument** — there is no `--search-file`, so the body-file trick cannot protect it. Author the keywords from the same restricted safe alphabet as the title (see §Title safety below); never paste transcript/evidence text (it can carry shell metacharacters the shell expands before `gh` sees argv, and it leaks raw evidence to GitHub as the query). `gh issue list` is a read (no rule-9 approval gate) — but its argument is author-it-never-paste-it all the same.

**Body contents — public evidence only.** The issue body quotes `spec/` and names the Spec / fixture / capability — the Spec number, matching the title template, never a `docs/EP/` proposal number. It does NOT paste private port source, the engineer's commits, transcripts, repo-local paths, or any text the engineer hasn't seen. Re-use spec text; describe the gap; show the minimal reproduction shape; stop.

**Shell safety for `gh issue create`.** Even the public-evidence-only body above — quoted `spec/` text, a fixture id, a sanitized minimal-reproduction shape — can carry shell metacharacters the user never inspects character by character (a `$`, a backtick, a `\` inside a quoted spec snippet). Never interpolate that text inline into the shell command (where `$`, `` ` ``, and `\` would expand). Instead, **write the body to a file with the `Write` tool**, then pass it with `gh`'s native `--body-file` flag — a single `gh issue create` invocation with no `cat` subshell, so it runs under the skill's `Bash(gh issue *)` permission. The `--body-file` path is a shell-safety mechanism only; it does **not** widen what the body may contain — the public-evidence-only boundary above still holds. This local recipe is the skill's own shell-safety core (each filing skill owns its recipe; `scripts/check_skill_mcp_drift.py` pins the load-bearing clauses):

1. **Settle the path first — as a value, not a template.** Before either tool call, decide one **concrete absolute path** and write it down. That single string is what you use twice, unchanged. Build it from two things you resolve yourself:

   - **The host's temp directory, as the concrete literal your session already shows you** — `/tmp` on a typical POSIX host, or whatever `TMPDIR` actually holds; `C:\Users\<you>\AppData\Local\Temp` on Windows. Never a fixed, shared name like `/tmp/spec-gap.md`: it fails on hosts without a POSIX `/tmp`, and a predictable name lets two rapid filings overwrite each other's redacted body.
   - **A per-filing nonce you pick yourself** — a few random characters, chosen once, before you call anything.

   **Neither tool below expands shell syntax, so an expression left in this path is a bug, not a placeholder.** `Write` takes `file_path` literally — it is not a shell, so `${TMPDIR:-/tmp}`, `$$`, `$RANDOM`, `$env:TEMP` or `$([guid]::NewGuid())` would land in the *filename* rather than being replaced. And a nonce expression re-evaluated at step 3 produces a **different** name, so the two steps would address two different files and `gh` would fail on a missing body. Substitute everything before the first tool call.

   So, having picked the nonce `7f3a9c`, the path is one concrete string:

   - **POSIX:** `/tmp/re-frame2-issue-7f3a9c.md`
   - **Windows:** `C:\Users\you\AppData\Local\Temp\re-frame2-issue-7f3a9c.md`

2. Use the `Write` tool to compose the body into that file. Its `file_path` is the concrete string from step 1, character for character — nothing left to expand.

3. File it with one `gh issue create` command, giving `--body-file` **that same string again** — never a re-typed fixed name, and never a freshly chosen nonce:

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "spec-gap(Spec NNN): <one-line>" \
     --body-file '/tmp/re-frame2-issue-7f3a9c.md'
   ```

   Single-quote the path. It is agent-authored, so nothing in it needs expanding, and single quotes keep a Windows path's backslashes literal.

   **File it unlabelled — do not add `--label`.** `gh` resolves label names to ids *before* the create mutation, so a single name the target repo does not carry kills the whole call at pre-flight (`could not add label: <name> not found`, exit 1) and the approved body is never filed. A published recipe cannot know which labels `day8/re-frame2` carries on the day it runs, so it names none; a maintainer labels the issue on arrival.

`--body-file` reads the body verbatim from disk, so no shell expansion ever touches the transcript-derived text, and the only `Bash` call is a bare `gh issue create` — exactly what the skill's `allowed-tools` grants.

**Title safety — the title is an inline argument; author it, never paste it.** `gh issue create` has no `--title-file` flag, so the `--body-file` trick that protects the body cannot protect the title. The `--title "spec-gap(Spec NNN): <one-line>"` argv is the one place the shell still expands text *before* `gh` receives it:

- **Never paste evidence-/transcript-derived text into `--title`.** A failure string, a log line, or a suggested title can carry `$(…)`, backticks, `"`, `'`, `\`, or a newline the shell expands the moment the command runs. Engineer approval to file an issue is not approval to execute session-carried shell syntax.
- **Author the title from a restricted safe alphabet:** letters, digits, spaces, and `- . , / ( ) :` only — no `$`, no backtick, no `"`/`'`, no `\`, no newline, no other shell metacharacter.
- **Reviewer pass covers the title.** Re-read the assembled `--title` arg in the same pre-emission pass that scans the body for private evidence. The same rule covers any other user-influenced argument (`--repo`, and `--search` above): keep them agent-authored or from a fixed set, never interpolated from evidence.

## 9. Approval gate before any cross-repo side effect

Filing a GitHub issue against `day8/re-frame2` from inside the engineer's port repo is a cross-repo side effect. **Per-issue approval IS required.** Before the call, show the engineer the full draft — title, target repo, body — and wait for an explicit OK. Invoking the skill is consent to the *workflow*, not consent to each *cross-repo write*. Treat the two as separate gates.

## 10. Honour the reserved `:rf/*` scheme — with the fixed three-fx unqualified carve-out

Framework ids live under the single root `:rf/*`; user code MUST NOT register there. Framework durable state lives in the **runtime-db partition** (`:rf.runtime/*` children), never under an app-db root — a stray `:rf/runtime` app-db root hard-errors (`:rf.error/legacy-runtime-root`). The one carve-out: the fx-ids `:dispatch`, `:dispatch-later`, `:raise` ship **bare** — register and recognise them exactly as-is; do not namespace or reject them. This is a conformance surface, not a style preference: fixtures assert `:rf.*` operation ids and emit the bare fx-ids from handler `:fx`. The reserved catalogue is [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/); the "which spec owns this surface" map is [`spec/Ownership.md`](https://day8.github.io/re-frame2/spec/Ownership/).

## 11. One path algebra, one canonical identity

re-frame2 has **one** `:rf/path` algebra and **one** canonical-identity rule (CEDN-1), stated once in [`spec/Conventions.md`](https://day8.github.io/re-frame2/spec/Conventions/) and inherited by every consumer — app-db/runtime-db focus, schema paths, classification paths, flow inputs/outputs, route params, resource keys, work ids. It is **not** a feature each subsystem reinvents: no private `overlap?`, no bespoke "stringify the params" cache key, no per-route canonicalizer. The semantics are normative immediately (the `:identity/*` fixtures pin them); the reference helpers are internal at this slice. Read the Conventions sections before EP 002 — the frame-focus surface stands on them.

---

## Anti-patterns (rule corollaries)

- **Don't copy the reference impl line-by-line and translate.** Copy the contract, not the realisation.
- **Don't skip the profile.** The mechanism choices (persistent data, concurrency) propagate through every line of Phase 2 code.
- **Don't read "minimum port" as "fewer required surfaces".** Minimum scopes the *optional* claim (the checklist's Q1–Q9, all defaulted to no) and the size of each required API — never the set of v1-required capabilities. Flows is the trap here: Spec 013 asks for restraint in how much flow surface you build, which is not permission to build none.
- **Don't ship without conformance.** Without the corpus passing, the port is "inspired by re-frame2", not a re-frame2 implementation. Bootstrap the harness seam first ([`phase-2-impl-order.md` §Step 0](phase-2-impl-order.md#step-0--bootstrap-the-feedback-seam)) and use the corpus as the test suite during development.
- **Don't stringify for identity, and don't let a digest become the stored key.** Use the canonical EDN rule; a digest is a derived, recomputable projection.
- **Don't let each subsystem grow its own path/identity logic** — that is the drift rule 11 exists to prevent.
- **Don't promise the engineer "this skill will write the port for you".** The skill is the map, not the vehicle; Phase 2 is multi-week work in any host.
