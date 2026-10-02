# Install and use skills

Install the re-frame2 skills so your agent can load their instructions when you ask for work. In Claude Code, skills can live globally in `~/.claude/skills/` or in a project's `.claude/skills/`. The installer below links all nine skills into the global directory.

## Install from a checkout

Clone the repository, install the links, and check them. If you already have a clone, start in that checkout instead of cloning again.

On macOS or Linux:

```bash
git clone https://github.com/day8/re-frame2.git
cd re-frame2
sh scripts/install-skills.sh
sh scripts/install-skills.sh --check
```

On Windows, in PowerShell:

```powershell
git clone https://github.com/day8/re-frame2.git
cd re-frame2
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1 -Check
```

A check that exits 0 means every skill links to this checkout. Exit 1 means at least one is missing, copied, or linked elsewhere; read the output for the affected skill.

Keep the checkout on disk: the installed links point to it. Updating it updates the installed instructions. Installing from another checkout repoints existing links there. The installer uses symlinks on macOS/Linux and directory junctions on Windows, without requiring admin rights.

## Run a skill

Start a new agent session in your application project. [Choose the skill](index.md#which-skill-do-i-want) for your task, then name it and the work you want:

> Using re-frame2, add a `:cart/remove` event that removes an item by id and test it.

Claude Code also exposes skills as slash commands, such as `/re-frame2`. Automatic selection uses the `description` in each `SKILL.md`; other hosts decide discovery and invocation in their own way.

If a skill is missing, run the installer's check mode against the same target and start a new agent session. If the check names a copied directory, see the options below before replacing it.

## Installation options

The options have the PowerShell spelling in brackets:

- `--check` (`-Check`) verifies the links without changing them.
- `--target DIR` (`-Target DIR`) chooses a different skill directory, such as your project's `.claude/skills/`. Use the same target when checking the installation. The checkout's own `skills/` directory is refused as a destination, including through an alias.
- `--force` (`-Force`) deletes non-link copies and replaces them with links. Keep any local edits first. Without force, the installer leaves those copies alone and exits 1; other skills may already have been linked.

For a project-local install, point the installer at that project's skill directory:

```bash
sh scripts/install-skills.sh --target /path/to/your-app/.claude/skills
sh scripts/install-skills.sh --target /path/to/your-app/.claude/skills --check
```

In PowerShell, use `-Target C:\path\to\your-app\.claude\skills` with both the install and check commands.

The other supported route is `npx skills add` against the public repo, which installs one skill directory. It does not install sibling skills: install a handoff target too when you need it. Nothing is published to npm and there is no Claude Code plugin marketplace entry; the `package.json` and `.claude-plugin/plugin.json` beside each skill are packaging metadata. The full installation contract is in [`skills/README.md`](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy).

## Runtime tools

Installing a skill supplies instructions. Runtime tools are configured separately when a task needs them; live pairing also needs its [MCP server and app preload](re-frame2-pair.md#one-time-setup), and Xray has its own [devtools setup](../xray/01-installation.md#add-the-dev-dependency).
