# Second sibling prompt review

The preserved [Grok](grok-prompt.md) and [Fable](fable-prompt.md) prompts now share much of Astra's earlier methodology: independent evidence layers, first-session setup, default-versus-matched policies, explicit request ledgers, viewer changes and failure/repair/failure controls. Repetition is convergence of instructions, not new evidence about Resources.

This round tightens experimental validity without adding more feature families:

- Define the starting state, completion order and observable oracle before each experiment. A generic backend fault cannot establish that a deduplication test detects duplicate requests.
- Include retained ownership during navigation. Late completion is not intrinsically incorrect when another consumer still needs the entry or the policy permits cache warming.
- Distinguish request order, authoritative server commit order and response arrival. Compare UI-local optimism, shared-cache updates and serialized writes against equivalent visibility requirements, while recording responsiveness and refetch costs.

Retained safeguards: no predetermined winning/losing control; no weighted parity headline; no inference from Resources-versus-managed-HTTP to Resources-versus-TanStack; no requirement that a worthwhile advantage be impossible elsewhere. A registry or conformance suite is an opportunity to demonstrate a benefit, not proof of superiority. Conduit remains the anchor rather than the ceiling of the comparison.

The [receipt](read-receipt.json) pins the copies reviewed. [Astra's preceding prompt](astra-prompt.md) is preserved separately. The resulting prompt remains a bounded research assignment, and the user's subsequent instruction authorizes executing it.
