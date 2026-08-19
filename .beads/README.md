# Beads - AI-native issue tracking

This repository uses Beads for issue tracking. Beads is a modern, AI-native tool designed to live directly in your codebase alongside your code.

## What is Beads?

Beads is issue tracking that lives in your repo. That makes it a good fit for AI coding agents and for developers who want their issues close to their code. You do not need a web UI - everything works through the CLI and integrates with git.

Learn more: [github.com/steveyegge/beads](https://github.com/steveyegge/beads)

## Quick start

### Essential commands

```bash
# Create new issues
bd create "Add user authentication"

# View all issues
bd list

# View issue details
bd show <issue-id>

# Update issue status
bd update <issue-id> --claim
bd update <issue-id> --status done

# Sync with Dolt remote
bd dolt push
```

### Working with issues

Issues in Beads are:
- git-native: stored in a Dolt database with version control and branching
- AI-friendly: the CLI-first design works well with AI coding agents
- branch-aware: issues can follow your branch workflow
- always in sync: issues auto-sync with your commits

## Why Beads?

AI-native design:
- built specifically for AI-assisted development workflows
- the CLI-first interface works with AI coding agents
- no context switching to web UIs

Developer focused:
- issues live in your repo, right next to your code
- works offline and syncs when you push
- fast, lightweight and stays out of your way

Git integration:
- automatic sync with git commits
- branch-aware issue tracking
- Dolt-native three-way merge resolution

## Get started with Beads

Try Beads in your own projects:

```bash
# Install Beads
curl -sSL https://raw.githubusercontent.com/steveyegge/beads/main/scripts/install.sh | bash

# Initialize in your repo
bd init

# Create your first issue
bd create "Try out Beads"
```

## Learn more

- documentation: [github.com/steveyegge/beads/docs](https://github.com/steveyegge/beads/tree/main/docs)
- quick start guide: run `bd quickstart`
- examples: [github.com/steveyegge/beads/examples](https://github.com/steveyegge/beads/tree/main/examples)

---

Beads: issue tracking that moves at the speed of thought.
