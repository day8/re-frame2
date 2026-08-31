# Hicasso's clj-kondo export

Macro-shape analysis for `defview`, `event` and `defhost`, shipped from the
artefact so a consumer's clj-kondo picks it up with no configuration of their
own.

## Installing it

```bash
clj-kondo --lint "$(clojure -Spath)" --dependencies --parallel --copy-configs
```

That copies this directory to `.clj-kondo/day8/re-frame2-hicasso/` and adds it
to your `:config-paths`. Nothing else is needed. Re-run it when you upgrade.

## What it does

`defview`, `event` and `defhost` are rewritten to their `defn`, `fn` and `def`
shapes, so kondo's ordinary analysis applies: a view's name resolves as a var,
a destructured prop is neither unresolved nor unused, arities are checked, and
a host's `opts` map is scanned for references. Optional docstrings are
accepted where the macros accept them. Without this every view name and every
prop reads as `Unresolved symbol`.

## What it deliberately does not do

No custom `:re-frame.hicasso/*` findings ship here. An earlier version
carried six bespoke behavioral checks — a hand-maintained scope grammar and
Hiccup walkers policing a narrow, non-authoritative slice — and they were
retired (rf2-r3r00). What they policed has more authoritative owners, and
not one owner. A bad head, a read outside render, a deferral crossing a
boundary — these the runtime refuses loudly at execution, with a named
`:rf.error/hicasso-*` id and a documented recovery route. An unkeyed mapped
child is React's own missing-key warning. A nameless interactive element is
the test kit's job, whose `ht/unnamed-controls` inspects the whole tree
rather than one form. And a read parked in a mutable reference is a
documented judgment call — forcing it inside another body is undefined
conduct, which nothing polices. A clj-kondo hook sees one form at a time and
does not know your program; a lint layer that guesses is one people learn to
ignore.
