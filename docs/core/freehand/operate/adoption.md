# Incremental adoption

You already ship on Reagent or UIx. The question is not “rewrite
everything” — it is **can Freehand land next to what I have?**

Yes, with clear boundaries. Freehand is another **view layer**, not a forced
replace. This page is the adoption path (habit comparison is separate).

## What stays shared

- `rf/reg-event`, `rf/reg-sub`, app-db, effects  
- frames, resources, machines, routing ownership  
- re-frame-side tooling  

Only **view spelling and host** change where you choose Freehand.

## Strategies (coarse to fine)

### 1. New surface only

Ship a new route, settings panel, or admin tool entirely in Freehand. Leave the
legacy tree alone. Lowest risk; proves mount, testing, and tooling.

### 2. Per route

Mount Freehand for route A and keep Reagent/UIx for route B. Each root has its own
DOM container (or you swap the main outlet on navigation). Share one frame when
the app-db should be shared; use separate frames only when isolation is the point.

### 3. Per region inside a page

Harder: two view layers on one screen. Prefer a clear DOM split (two containers)
over interleaving Hiccup dialects in one parent. If a Reagent parent must host a
Freehand island, use a mount into a child DOM node Freehand owns, or expose
Freehand through `v/->react` into a React/UIx parent that already expects a
component value.

### 4. Per component (leaf migration)

Rewrite leaf controls to Freehand first (forms, tables), keep page shells on the
old adapter until the leaves stabilize. Library-style Freehand leaves can compile
early; pages often stay interpreted.

## Embedding Freehand in an existing React / UIx tree

When the parent is already React:

```clojure
;; parent is UIx/React; child is Freehand
{:cellRenderer (v/->react person-cell)}
;; pass a live :frame when ambient Freehand context is absent
```

Rules:

- `v/->react` **never creates** a frame — supply a live one.  
- Freehand owns that island’s `sub` and data events.  
- Do not expect Freehand Hiccup and Reagent Hiccup to compose as one dialect.  

## Embedding host React in Freehand

The reverse is the normal Freehand host story: qualified leaves, behaviors, and
small React host components for hooks.

## What not to do

| Approach | Problem |
|---|---|
| Mix Reagent and Freehand components as if they were the same Hiccup | Different hosts and contracts |
| Share ratoms “for a while” into Freehand | Freehand will not adopt that model |
| Mount Freehand without a frame story | subs and events need a live frame |
| Expect automatic whole-app migration | Freehand is opt-in per surface you choose |

## Suggested rollout checklist

1. Install Freehand next to the existing adapter (implementation dependent until
   coordinates land).  
2. Mount a **toy route** end-to-end (counter shape).  
3. Add one headless intent assertion without a browser.  
4. Migrate one real form.  
5. Use inspect / hot-views habits before performance work.  
6. Only then promote hot leaves with compilation.  
7. Keep adapters for the long tail until there is a reason to move them.  

## Donor `re-frame.ui`

If you already use `re-frame.ui`, treat it as **donor machinery** under Freehand’s
compiled path over time, not a permanent second public product. Programme migration
notes publish when the absorption gate is ready.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Mixed Freehand + Reagent in one parent | do not interleave dialects; island at a root or host boundary |
| Need published coords today | use a shipping adapter; Freehand is pre-alpha `:local/root` |

