# Cardinal rules — re-frame2 implementor

These hold across every phase of building a new re-frame2 implementation. Each rule expands the one-line form carried in `SKILL.md`.

---

## 1. The spec is the contract

[`spec/`](../../../spec) is the source of truth. The CLJS implementation under `implementation/` is one worked example of how to realise the contract — not the contract itself. When the reference impl and the spec disagree, the spec wins; draft a GitHub issue against `day8/re-frame2` (against the spec or the reference impl as appropriate) and ask the engineer to OK it before filing — see rule 9.

**Pin the spec before reading it.** The kickoff prompt names a specific `day8/re-frame2` commit/tag; Phase 1 records that pin in `DECISIONS.md` (the preamble before D1 — see [`decision-record.md`](decision-record.md)). Before reading the spec corpus, verify `git -C <path-to-re-frame2> rev-parse HEAD` matches the pin **and** that the origin is `https://github.com/day8/re-frame2`. The engineer actually runs this check in the Phase 1 preamble ([`phase-1-decisions.md`](phase-1-decisions.md)). An unpinned or unverified checkout is not a contract — it's whatever happens to be on the filesystem.

## 2. Phase 1 before Phase 2

Decisions made under Phase 1 (host language, substrate, scope, identity primitive, concurrency model) are load-bearing for every line of code written in Phase 2. Lock them in writing before opening an editor. If a Phase 2 step forces a Phase 1 decision to change, **stop**, revise the decision record, and restart that Phase 2 step.

## 3. Implement in dependency order

EP 001 (Registration) → 002 (Frames + events + effects + subs) → 006 (Reactive substrate) → 004 (Views) → 009 (Instrumentation) → 015 (Data Classification) are the foundation. Acceptance gate 1 — running the `:core/*` conformance fixtures — sits at the end of this cluster; 009 is in the foundation because `:core/trace` and `:core/error` fixtures exercise it, and 015 immediately follows because it is **v1-required** ([`spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md), [`spec/API.md`](../../../spec/API.md) exposes the frame-owned `:sensitive`/`:large` classification + `project-egress` boundary as v1 per EP-0015) and overlays the 009 emission boundary — ship the core gate without it and classified values leak through trace, the always-on observability sink records, Xray, MCP, and log sinks. The optional EPs (005, 007–008, 010–014) sit downstream and can be deferred or skipped per Phase 1 scope.

## 4. Substrate-agnostic phrasing in code and docs

The reference impl is a CLJS+Reagent realisation — keywords as ids, hiccup as render-tree, Reagent ratoms as the reactive container. These are *choices the reference made*. Your port chooses differently and re-frame2 is still re-frame2. Do not write code that assumes "hiccup" or "Reagent" or "keyword" universally; write code that assumes "the identity primitive", "the render-tree", "the reactive container".

## 5. No core.async equivalents

The CLJS reference does not use core.async, and ports inherit this directive. Every in-scope host cross-compiles to JS, so async effects schedule via the JS event loop's primitives (Promise / setTimeout / setImmediate / queueMicrotask); cross-frame work is serialised per frame via the run-to-completion drain. If your host's idiomatic concurrency model is channel-shaped, you may use channels *internally* — but the public dispatch contract is still the run-to-completion drain.

## 6. JVM-runnability for the testing surface

Where the CLJS reference makes `compute-sub` and `machine-transition` pure-function-callable from JVM, your port's analogue must be callable from a non-substrate test harness. Pure transitions and pure sub computations are the bedrock of the test story.

## 7. Conformance corpus is the acceptance test

[`spec/conformance/`](../../../spec/conformance) is the verification mechanism. Your port runs the fixtures whose capabilities are a subset of what you've claimed; the score is `passed / claimed-applicable`. A fixture you cannot make pass without consulting outside sources is a **spec gap**, not an implementation gap — draft a GitHub issue against `day8/re-frame2` against the spec corpus, then file with engineer approval (rule 9).

## 8. If you find a spec gap, draft a GitHub issue and ask before filing. Do not paper.

When implementing surfaces a missing surface, an inconsistency, or an undocumented decision, that's a spec finding. **Draft** a GitHub issue against `day8/re-frame2`, show the engineer the title + body, and **ask for explicit OK before running `gh issue create`** (rule 9). Do not silently invent an answer; do not extrapolate from "what the reference impl does" if the spec is silent.

`bd` (beads) is the re-frame2 monorepo's internal tracker and is **never invoked from a published skill**. The implementor skill runs in the engineer's port repo; spec gaps reach the framework maintainers via the upstream repo's GitHub issues — and only after the engineer has OK'd the specific issue body.

**Search before filing — reference an existing issue instead of duplicating.** A spec gap you hit (an ambiguous fixture, a missing surface) may already be tracked upstream. Before drafting, list `day8/re-frame2`'s existing issues for the same gap and, if one matches, reference it instead of filing a duplicate — show the engineer the existing issue and stop:

```bash
gh issue list --repo day8/re-frame2 --search "<agent-authored safe keywords>"
```

**`--search` is an inline shell argument — author the keywords, never paste them.** There is no `--search-file`; the search string is interpolated into `--search "<keywords>"` argv exactly like `--title`, so the body-file trick cannot protect it. The keywords MUST be **agent-authored summaries from the same restricted safe alphabet as the title** (letters, digits, spaces, and `- . , / ( ) :` only) — never copied from the transcript, an error string, a suggested title, a fixture's raw contents, or any other evidence. A query lifted from evidence can carry `$(…)`, backticks, `"`, `'`, `\`, or a newline that the shell expands *before* `gh` receives argv (the same transcript→shell injection the `--title` rule closes), and it would also send the raw evidence text to GitHub as the search query. Re-read the assembled `--search` in the same pre-emission reviewer pass that scans the title and body (rule 8 §body, rule 9 approval gate); if any shell metacharacter survived, rewrite it from the safe alphabet. `gh issue list` is a read; it does not need the per-issue approval gate (rule 9) — only `gh issue create` does — but its argument is author-it-never-paste-it all the same.

**Body contents — public evidence only.** The issue body quotes `spec/` and names the EP / fixture / capability. It does NOT paste private port source, the engineer's commits, transcripts, repo-local paths, or any text the engineer hasn't seen. Re-use spec text; describe the gap; show the minimal reproduction shape; stop.

**Shell safety for `gh issue create`.** Even the public-evidence-only body above — quoted `spec/` text, a fixture id, a sanitized minimal-reproduction shape — can carry shell metacharacters the user never inspects character by character (a `$`, a backtick, a `\` inside a quoted spec snippet). Never interpolate that text inline into the shell command (where `$`, `` ` ``, and `\` would expand). Instead, **write the body to a file with the `Write` tool**, then pass it with `gh`'s native `--body-file` flag — a single `gh issue create` invocation with no `cat` subshell, so it runs under the skill's `Bash(gh issue *)` permission. The `--body-file` path is a shell-safety mechanism only; it does **not** widen what the body may contain — the public-evidence-only boundary above still holds, so never let "it's in a file now" become a reason to paste private port source, logs, or transcript-derived text:

1. Use the `Write` tool to compose the body into a **fresh, per-filing temp file in the host OS's temp directory** — never a fixed, shared, predictable name. A hard-coded `/tmp/spec-gap.md` fails on hosts without a POSIX `/tmp` (Windows consumer installs) even after the engineer approved filing, and its predictable name lets two concurrent or rapid filings overwrite each other's redacted body — filing the wrong public-evidence text to GitHub or leaving the evidence in a shared location. Pick the path for the OS and add a per-filing nonce, then **carry that exact path into `--body-file` below** (matches `skills/shared/issue-filing.md` §Shell-safety):

   - **POSIX:** `${TMPDIR:-/tmp}/re-frame2-issue-$$-$RANDOM.md`
   - **Windows (PowerShell):** `$env:TEMP\re-frame2-issue-$([guid]::NewGuid()).md`

   ```markdown
   ## Context
   …port and EP being implemented…

   ## What the spec is silent on
   …concrete surface, with quotes from spec/<EP>.md…

   ## Why this is a spec gap, not a port bug
   …cannot be resolved without consulting outside sources…
   ```

   The body is plain markdown — no shell escaping needed; nothing expands it.

2. File it with one `gh issue create` command (`--body-file` is the exact per-filing path you wrote in step 1, never a re-typed fixed name):

   ```bash
   gh issue create \
     --repo day8/re-frame2 \
     --title "spec-gap(EP-NNN): <one-line>" \
     --body-file "<the per-filing temp path you wrote in step 1>" \
     --label spec-gap,from-implementor
   ```

`--body-file` reads the body verbatim from disk, so no shell expansion ever touches the transcript-derived text, and the only `Bash` call is a bare `gh issue create` — exactly what the skill's `allowed-tools` grants. Pattern is documented in `skills/README.md` §Published-skill `allowed-tools` baseline.

**Title safety — the title is an inline argument; author it, never paste it.** `gh issue create` has no `--title-file` flag, so the `--body-file` trick that protects the body cannot protect the title. The `--title "spec-gap(EP-NNN): <one-line>"` argv is the one place the shell still expands text *before* `gh` receives it — same untrusted-evidence threat model as the body, projected onto `--title`:

- **Never paste evidence-/transcript-derived text into `--title`.** A failure string, a log line, or a suggested title can carry `$(…)`, backticks, `"`, `'`, `\`, or a newline that the shell expands the moment the command runs — bypassing the no-interpolation boundary even when the body is safe. Engineer approval to file an issue is not approval to execute session-carried shell syntax.
- **Author the title from a restricted safe alphabet:** letters, digits, spaces, and `- . , / ( ) :` only. The `spec-gap(EP-NNN): <one-line>` pattern fits this alphabet — fill the `<one-line>` with summarised, agent-written text containing no `$`, no backtick, no `"`/`'`, no `\`, no newline, and no other shell metacharacter (`;`, `|`, `&`, `<`, `>`, `*`, `?`, `[`, `]`, `{`, `}`, `!`, `~`).
- **Reviewer pass covers the title.** Re-read the assembled `--title` arg in the same pre-emission pass that scans the body for private evidence (rule 8 §body, rule 9 approval gate). If any shell metacharacter or evidence text survived, rewrite the title before running the command. The same rule covers any other user-influenced argument (`--label`, `--repo`): keep them agent-authored or from a fixed set, never interpolated from evidence.

## 9. Approval gate before any cross-repo side effect

Filing a GitHub issue against `day8/re-frame2` from inside the engineer's port repo is a cross-repo side effect. **Per-issue approval IS required.** Before the call, show the engineer the full draft — title, target repo (`day8/re-frame2`), label set, body — and wait for an explicit OK. "Continuing in a moment" is not enough; the engineer types "go" / "yes" / "file it" or the skill does not run `gh issue create`. The cost of a delayed file is minutes; the cost of an unwanted cross-repo issue is permanent and visible.

Invoking the skill is consent to the *workflow*, not consent to each *cross-repo write*. Treat the two as separate gates.

## 10. Honour the reserved `:rf/*` namespace scheme

re-frame2 reserves **one root keyword namespace** for framework-owned ids: `:rf/*` (and its sub-namespaces — `:rf.fx/*`, `:rf.machine/*`, `:rf.error/*`, `:rf.registry/*`, …). Every framework runtime id — events, fx, cofx, trace operations, error categories, warnings, registrar mutations, the default frame id (`:rf/default`) — lives under that root. **User code MUST NOT register handlers, fx, subs, or frames under `:rf/*`.** Your port must (a) emit framework ids under the reserved scheme and (b) leave the scheme free for the framework, never user code. (Note: `:rf/default` is a `:rf/*`-scheme keyword, but under EP-0002 it carries **no framework privilege** — it is not auto-created, not a resolution fallback, just an ordinary frame id a small app or migration may explicitly register and select. The runtime never infers it.)

**Framework durable state lives in the runtime-db partition, NOT an app-db root.** A frame's state is two partitions: the app's own state (app-db) and the framework-owned **runtime-db** partition, exposed (internally) as the reserved coeffect/effect key `:rf.db/runtime`. Framework runtime state — machines, routing, elision, SSR — lives under runtime-db, addressed by the `:rf.runtime/*` subsystem children (`:rf.runtime/machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr`). There is **no `:rf/runtime` app-db root** — a stray `:rf/runtime` root in a `:db` effect is a **hard error** (`:rf.error/legacy-runtime-root`) thrown at the event-commit boundary (no warning, no alias). Your port keeps framework runtime state in runtime-db so an ordinary `:db` return replaces only app-db and cannot touch (or accidentally clobber) the runtime partition. Per [`spec/Conventions.md` §Reserved partition keys](../../../spec/Conventions.md) and §Reserved runtime-db keys.

**Carve-out — three reserved *unqualified* fx-ids.** The "every framework id lives under `:rf/*`" rule has **one explicit exception**: the fx-ids `:dispatch`, `:dispatch-later`, and `:raise` ship as **bare, unqualified** reserved ids, NOT under `:rf/*`. They are load-bearing at the centre of every event drain (`:dispatch` / `:dispatch-later` are recognised by the runtime `do-fx`; `:raise` is recognised by the machine handler). Per [`spec/Conventions.md` §Reserved fx-ids](../../../spec/Conventions.md). Your port MUST register and recognise these three exactly as `:dispatch` / `:dispatch-later` / `:raise` — **do not namespace them, do not reject them, do not let a "must be `:rf/*`" validator flag them.** Core conformance fixtures emit `:dispatch` / `:dispatch-later` from handler `:fx`; a port that namespaces or rejects the bare ids breaks dispatch/drain and fails those fixtures. The rule for *new* framework fx-ids is `:rf.<surface>/*` (or `:rf.fx/*` for generic ones) — the carve-out is a fixed, additive-only set of exactly three, not a license to coin new unqualified ids. **User code still namespaces its own fx** (`:auth.login/issue-request`, `:my-lib.fx/store`) to avoid colliding with the reserved unqualified set.

This is a conformance surface, not a style preference. Fixtures assert `:rf.*` operation ids on the trace stream and reserved app-db keys at known paths; a port that invents its own framework-id namespace, or lets app code squat `:rf/*`, fails them. The reserved set, the reserved fx-ids (including the three unqualified ones above), and reserved app-db keys are catalogued in [`spec/Conventions.md`](../../../spec/Conventions.md). The "which spec owns this surface" map — the single most useful index for a port author asking "where does X live?" — is [`spec/Ownership.md`](../../../spec/Ownership.md). Consult [`spec/API.md`](../../../spec/API.md) for the consolidated public signatures when wiring each EP's surface.

## 11. One path algebra, one canonical identity

re-frame2 has **one** path algebra and **one** canonical-identity rule, stated once and inherited by every consumer — app-db / runtime-db focus, schema paths, redaction-mark paths, flow inputs and outputs, route params, resource cache keys, work ids, and future feature-module declarations (the `:rf/path` algebra + canonical EDN identity, EP-0012). It is **not** a feature each subsystem reinvents. The reference helpers (`rf.path/{get,lookup,put,over,compose,prefix?,overlap?,instantiate}` and `rf.identity/{canonical,canonical-bytes}`) are **internal** at this slice — there is no `re-frame.core` facade export and no facade classification yet; an op graduates to a public name only once **two or more** consumers use it through the internal namespace without a shape change. The *semantics*, though, are **normative immediately**: a port realises the foundation from the start because flows, schemas, routing, and resources all build on it, even though the public surface stays small. **No subsystem may keep a private ad hoc overlap, canonicalization, or path-round-trip implementation once the shared helpers exist** — there is no "tool-only" path semantics, and any public helper obeys the identical laws.

The full contract the port must realise — concrete path vectors over the shared segment domain; the `[]` **root path** with the root-path laws (a `put` delegating to raw host `assoc-in` is non-conforming, because `(assoc-in {:a 1} [] x)` assocs under key `nil`); the path laws and the **symmetric `overlap?`** flows/schemas/resources all share; declaration normalization to a vector with templates stored ONLY as `[:rf.path/param <name>]` (the `'?name` sugar never reaches a stored/serialized shape); fail-closed `CEDN-1` canonical identity (key-order-irrelevant, never stringification, `:rf.error/non-edn-identity` on out-of-domain values, digest derived-only); scoped resource keys `[canonical-scope resource-id canonical-params]` + work ids on that one rule; and canonical route emission (deterministic query order, `nil` elided — the prism laws, consumer-wired in routing) — is the canonical statement in [`phase-2-impl-order.md` §The shared path + identity foundation (EP-0012)](phase-2-impl-order.md#the-shared-path--identity-foundation-ep-0012). Read it there once rather than re-derived here; the anti-pattern corollaries below are the failure modes that contract guards against.

Per [`spec/Conventions.md` §The `:rf/path` algebra / §Canonical EDN identity / §Canonical byte encoding (`CEDN-1`)](../../../spec/Conventions.md) (EP-0012).

---

## Anti-patterns (rule corollaries)

- **Don't copy the reference impl line-by-line and translate.** The reference uses macros for source-coord capture; your host may not have macros. The reference uses Reagent's automatic dependency tracking; your host may need explicit subscriptions. The reference uses keywords; your host may use branded strings. *Copy the contract, not the realisation.*
- **Don't skip Phase 1.** Decisions made under Phase 1 (especially F2 persistent data structures and F5 concurrency model) propagate through every line of Phase 2 code. Engineers who skip Phase 1 to "just start coding" end up rewriting the foundation halfway through.
- **Don't declare Q1 (state machines) `yes` unless the FSM substrate is genuinely required.** EP 005 is substantial work and gates a large block of code. Smaller ports ship `Q1=no` and add the FSM substrate later — the events/subs/fx/views triad is self-sufficient for many use cases.
- **Don't ship without conformance.** The corpus is the only objective measure of "is this re-frame2?" Without the corpus passing, the port is "inspired by re-frame2" but not a re-frame2 implementation. Run the harness early; the corpus is also useful as your test suite during development.
- **Don't let each subsystem grow its own path/identity logic.** A private `overlap?` in flows, a bespoke "stringify the params map" cache key in resources, a per-route canonicalizer — each is the per-subsystem redefinition rule 11 exists to prevent. They drift: one orders map keys, another doesn't; one fails closed on a host object, another `JSON.stringify`s it. Build the shared `:rf/path` algebra + canonical identity once and route every consumer through it.
- **Don't stringify for identity.** `str` / `pr-str` over an unordered host map, `JSON.stringify`, and object identity all *look* like identity but break it across hosts (insertion order, reference identity, host number formatting). Use the canonical EDN rule; it is key-order-irrelevant and cross-host stable by construction.
- **Don't let a digest become the stored key.** A digest is a derived, recomputable projection for size-constrained surfaces — never the authoritative identity. Store the canonical EDN value; recompute the digest from it when a surface needs the smaller form.
- **Don't ship `'?name` template sugar into stored shapes.** The quote-symbol spelling is declaration-boundary sugar; normalize it to `[:rf.path/param <name>]` before anything is stored, serialized, traced, or made a cache key. Two spellings of one template variable is two identities for one fact.
- **Don't promise the engineer "this skill will write the port for you".** The skill walks the workflow and surfaces the decisions; the engineer (or their Claude session) writes the code. Phase 2 is multi-week work in any host; the skill is the map, not the vehicle.
