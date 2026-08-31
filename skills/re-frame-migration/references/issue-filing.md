# Filing an upstream issue against `day8/re-frame2`

When a migration turns up something worth reporting upstream — a genuinely
ambiguous rule (cardinal rule 1), spec drift, a missing M-rule, surprising
behaviour — file a GitHub issue against `day8/re-frame2`. Announce the
cross-repo filing to the author first; a cross-repo write is a separate gate
from the migration itself.

1. **Search before filing.** `gh issue list --repo day8/re-frame2 --search
   "<keywords>"` — if an existing issue matches, reference it instead of
   duplicating. `--search` is an inline shell argument (there is no
   `--search-file`), so author the keywords from the safe alphabet below;
   never paste transcript, code, or error text into it.
2. **Compose the body with the `Write` tool** into a fresh, per-filing temp
   file (nonce-carrying `${TMPDIR:-/tmp}/…` on POSIX, `$env:TEMP\…` on
   Windows — never a fixed, predictable name). Never interpolate
   transcript-derived text inline into a shell command, where `$`,
   `` ` ``, and `\` expand. The body cites rule ids, quotes `spec/` /
   `MIGRATION.md` text, and shows a minimal reproduction shape — not the
   author's private code, paths, or anything they haven't seen.
3. **File with one command:** `gh issue create --repo day8/re-frame2
   --title "migration: <one-line>" --body-file "<the exact path from
   step 2>"`. `--body-file` reads the body verbatim from disk, so no shell
   expansion touches it, and the single bare `gh issue create` runs under
   the skill's `Bash(gh issue *)` permission.
4. **Title safety.** `gh issue create` has no `--title-file`, so the
   body-file trick cannot protect the title. Author the title — and every
   other inline argument (`--label`, `--repo`, search keywords) — from the
   restricted safe alphabet: letters, digits, spaces, `- . , / ( ) :` only.
   Never paste evidence into `--title`; re-read the assembled title in the
   same pre-emission pass that scans the body.
5. **Close the loop.** Reference the issue number in the migration summary
   ([`output-format.md`](output-format.md)); don't bury the finding in
   prose. (`bd` is monorepo-internal and never invoked from a published
   skill.)
