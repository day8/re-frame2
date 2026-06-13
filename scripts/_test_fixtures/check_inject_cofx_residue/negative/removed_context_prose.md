# Effects and coeffects (negative fixture)

Removed-context prose that NAMES the retired surfaces. None of this is inside a
code fence, so the gate must stay GREEN.

Coeffects are not delivered by an interceptor in v2. v1's `inject-cofx`
interceptor is removed (hard error `:rf.error/inject-cofx-removed`); coeffect
delivery is now the `:rf.cofx/requires` registration declaration. The
`:rf.world/inputs` dispatch opt is likewise retired — renamed to `:rf.cofx`.

Five v1 interceptors are removed (`debug`, `trim-v`, `on-changes`, `enrich`,
`after`); so is `inject-cofx`.
