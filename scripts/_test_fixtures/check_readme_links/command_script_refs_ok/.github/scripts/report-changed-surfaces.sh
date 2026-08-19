#!/bin/sh
# Fixture stand-in under a DOTFILE directory.  This is the tooth for the
# leading-dot path segment: a rule that only accepted segments starting with a
# word character would drop `.github/**` — and `.github/scripts/` is exactly
# where the references the mayor loop runs actually live.
exit 0
