# Inline code-span mention (negative fixture)

The retired spellings named inside single-backtick INLINE code spans are prose
(a name being discussed), not a fenced snippet to copy. The gate scans only
fenced blocks, so these must stay GREEN.

A handler opts in with `:rf.cofx/requires`; v2 has no `inject-cofx` interceptor.
The `:rf.world/inputs` opt is gone. Calling `inject-cofx*` is a hard error.
