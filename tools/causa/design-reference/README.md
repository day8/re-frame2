# Causa — design reference (Figma export)

This is the **Figma Make export** for the Causa visual redesign — the **visual source of
truth** for **rf2-ad7zx** (Causa orange redesign). It is a React/Tailwind rendering of the
design captured in the Figma brief; it is **reference only — not part of the build** (not on
the classpath, not compiled, not shipped).

## What's here (curated)

The Figma export's *custom* pieces only — the generic shadcn/ui primitives, the figma
helpers, and the node/vite config are intentionally omitted (they're library boilerplate,
not Causa design):

- `App.tsx` — the **five-region layout** (Chrome ribbon → Events ribbon → Event list → tab
  strip → panel content).
- `components/` — the regions + the **seven panels**: `ChromeRibbon`, `EventsRibbon`,
  `EventList`, `Tabs`, `EventPanel`, `AppDbPanel`, `ViewsPanel`, `TracePanel`,
  `MachinePanel`, `RoutesPanel`, `IssuesPanel`.
- `styles/` — `devtools.css` (the token scale + colours), `globals.css`, `theme.css`.

## Fidelity rule — keep the Figma design, don't go off script

When implementing Causa (CLJS / hiccup) against this:

1. **Translate faithfully** — match this layout, these components, this structure. Do **not**
   redesign, "improve", or revert to the prior Causa look.
2. **Swap the colour identity to ORANGE.** The export shipped a generic GitHub-blue accent
   (`--devtools-active: #0969da`); the real identity is **orange** per
   [`../spec/022-Design-Tokens.md`](../spec/022-Design-Tokens.md) (`accent #F97316`/`#EA580C`,
   `accent-static` cyan for Static mode). The blue is the *only* deliberate divergence.
3. **Where the prior spec (007-UX-IA / 021-Dynamic-Panel-Designs) differs from this design,
   the Figma design wins** — they reconcile to it. The lone carve-out is functional semantic
   colours the mock didn't render (redacted / pair-origin / perf tiers), kept because the
   framework needs them — not licence to re-richen the look.

Per rf2-ad7zx.
