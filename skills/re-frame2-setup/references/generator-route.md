# The generator route

The exact pre-publish command the skill runs when the author asks for the generator (`SKILL.md` cardinal rule 4), its `-Tnew` prerequisite, and the two coordinates the command must keep apart. The manual route never reads this leaf.

## Prerequisite — the `-Tnew` tool

`clojure -Tnew create …` is [deps-new](https://github.com/seancorfield/deps-new)'s standard tool, installed globally under the alias `new`. Without it the command fails with an unknown-tool error before a single file is emitted, and the failure says nothing about re-frame2. The skill's `allowed-tools` grant covers `-Tnew create` but deliberately **not** `clojure -Ttools`: installing a tool writes outside the project, into the author's Clojure tools directory, which is the author's call rather than a scaffolder's. So if `-Tnew` is missing, say so and hand over the one line:

```bash
clojure -Ttools install-latest :lib io.github.seancorfield/deps-new :as new
```

…then continue once the author has run it. The manual 6-step path needs no tool at all and is always available instead.

## The pre-publish command

The command has **two independent coordinates**, and one shared working directory cannot carry both — conflating them makes the command fail on its first use:

- **Where the template comes from** is the `:local/root`. It must be the **absolute** path to `tools/template` inside your reviewed re-frame2 checkout. `:local/root` is resolved against the *command's* working directory, so a relative `"tools/template"` means `<the directory you are standing in>/tools/template` — which, from a fresh project directory, does not exist, and the command dies with `Local lib day8/re-frame2-template not found` before deps-new loads the template.
- **Where the project lands** is the working directory. deps-new creates the project in a new child directory named after `:name`'s artefact, so run the command from the directory that should *contain* the new project folder.

Write the absolute path with **forward slashes on every OS** — Java accepts them on Windows too, and it keeps the EDN string free of hand-authored `\\` escaping:

```bash
# Standing in the directory that should CONTAIN the new project.
# <RE_FRAME2> = absolute path of your reviewed re-frame2 checkout,
# e.g. /home/you/code/re-frame2 or C:/Users/you/code/re-frame2
clojure -Sdeps '{:deps {day8/re-frame2-template {:local/root "<RE_FRAME2>/tools/template"}}}' \
        -Tnew create :template day8/re-frame2-template :name acme/my-app
```

That emits `./my-app/`. Add `:substrate :uix` for the UIx variant. The emitted `deps.edn` carries the two `day8/re-frame2*` coordinates as `:mvn/version`, which does not resolve until the framework is on Clojars — so the generator route continues exactly where the manual one does, at [`SKILL.md`](../SKILL.md) step 2: point those two coordinates at the reviewed checkout with `:local/root` (`<RE_FRAME2>/implementation/core` and `<RE_FRAME2>/implementation/adapters/reagent`, or `…/adapters/uix`, and Story's `:dev` path at `<RE_FRAME2>/tools/story`), then `cd my-app && npm install && npx shadow-cljs compile app && npx shadow-cljs watch app`.

Already standing *inside* the empty directory you want the app generated into? Add deps-new's own target options — `:target-dir . :overwrite true`. The `:overwrite` is required because deps-new refuses an existing target directory (`. already exists (and :overwrite was not true)`), and `.` always exists.

**The skill resolves `<RE_FRAME2>` itself** — it is installed by link from a reviewed checkout ([`README.md` §Install the skill in Claude Code](../README.md#install-the-skill-in-claude-code)), so `SKILL.md`'s own resolved location is `<RE_FRAME2>/skills/re-frame2-setup/SKILL.md` and the template is `<RE_FRAME2>/tools/template`. If the skill was reached some other way and no such checkout is on disk, say so and fall back to the manual 6-step path rather than guessing a path.
