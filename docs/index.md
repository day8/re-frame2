# re-frame2

**Alpha. But it works.**

re-frame2 is a pattern for building Single Page Apps on a virtual-DOM substrate — React, in practice. You build with **Reagent v2**, **Reagent-slim** (a Reagent rewrite that knows about re-frame2), **UIx**, or **Helix**. Production apps can be built on it today.

## What's novel

**Views are derivative, not central.** Events update centralised state. Subscriptions derive data. Views sit at the end as render functions over reactive inputs. No `useState`, no `useEffect`, no "lifting state up" — state was never down there in the first place. Your app is a small virtual machine; handlers are the instruction set; events are the program.

**Tooling is first-class.** The runtime is a predictable pipeline with a single deeply integrated trace bus. Devtools, AI pair-programmers, story tools, test harnesses — they all attach to one surface and get the whole picture for free. Source-coord stamping on every handler and DOM element gives click-to-source from any panel.

> *Your language of choice should be Turing complete; your architecture shouldn't be.*
