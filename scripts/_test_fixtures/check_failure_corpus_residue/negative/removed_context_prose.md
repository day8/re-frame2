# Removed-context prose (negative fixture)

The `:where :cofx` spelling named in PROSE (outside any code fence) is a name
being discussed as retired, not a live snippet. The gate scans only fenced
blocks, so this must stay GREEN.

The `:where :cofx` schema-validation surface was retired in EP-0017: a
recordable coeffect failing its `reg-cofx` `:schema` is now the halting
`:rf.error/cofx-value-invalid` hard error, and it does not emit a `:where :cofx`
trace at all.
