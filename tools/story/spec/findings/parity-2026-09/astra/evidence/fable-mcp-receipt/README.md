# Attributed Fable MCP receipt

Copied during sibling synthesis from the scratch root identified in [Fable's evidence document](../../../fable/evidence.md): `C:/Users/miket/AppData/Local/Temp/claude/C--Users-miket-code-re-frame2/42c31ef4-cd81-4ccf-920a-6070745a6efa/scratchpad`.

These are **Fable's executions**, inspected by Astra, not a new Astra MCP run:

- [Prelude](mcp-prelude.clj) installs the plain-atom adapter and a variant with setup value 1, expected value 2.
- [Requests](mcp-requests2.jsonl), request 3, replace the same variant with setup value 1, expected value 1.
- [Responses](mcp-out2.jsonl), requests 2 and 4, report fail then pass with corresponding expectations.

They support working JVM tool transport, same-ID re-registration and rerun. They do not demonstrate an application defect repaired while retaining its expectation or a connection to the Story browser runtime. Original scratch files were left intact.
