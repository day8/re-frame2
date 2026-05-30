# Guide

You want to build a serious browser app without turning the codebase into a haunted wardrobe of callbacks, local state, and heroic debugging rituals. This guide teaches re-frame2 as a small set of repeatable moves: state is data, change is events, reads are subscriptions, views are derivatives, effects are named requests, and tools get their power because the runtime can explain itself.

This is not an API reference. The reference tells you every knob. The guide teaches the shape of the machine, why that shape exists, and how to use it without becoming the person who says "just one more useEffect" in a meeting and ruins everyone's afternoon.

## How to read it

Start with chapters 01 through 07 if you are new to re-frame. That gives you the core loop: `app-db`, events, subscriptions, views, effects, and coeffects. You can write a useful app after that.

Chapters 08 through 13 teach the parts that make a codebase robust: schemas, interceptors, HTTP, forms, machines, and tests. These are where re-frame2 starts to feel less like a UI library and more like an operating discipline for applications.

Chapters 14 through 26 cover production pressure: errors, performance, observability, tooling, frames, routing, server rendering, adapters, privacy, configuration, migration from re-frame v1, and the habits that keep a large app pleasant.

Some pages include runnable `cljs-rf2` cells. Click into the cell, edit the code, and press `Ctrl-Enter` or `Cmd-Enter`. The point is not gimmickry. The point is to let you change the machine while it is sitting in front of you.

## The chapters

| # | Chapter | Job |
|---|---|---|
| 01 | Introduction | Build the mental model: one loop, six steps, no mystery fog. |
| 02 | app-db | Learn where state lives and how to shape it. |
| 03 | First app | Build the counter in a real project shape. |
| 04 | Events and the cascade | Watch one event run through the whole runtime. |
| 05 | Subscriptions | Put reads behind named derivations. |
| 06 | Views | Render data without smuggling logic into the DOM. |
| 07 | Effects and coeffects | Push impurity to named edges. |
| 08 | Schemas | Make invalid data loud while the code is still cheap to fix. |
| 09 | Interceptors | Learn the sandwich around handlers. |
| 10 | HTTP | Treat the network as a managed effect, not a side alley. |
| 11 | Forms | Build forms as state machines, not scattered flags. |
| 12 | Machines | Model flows where state names matter. |
| 13 | Testing | Test the system at the cheapest truthful layer. |
| 14 | Errors | Understand failure without losing the plot. |
| 15 | Performance | Spend less time guessing why the app is slow. |
| 16 | Observability | Read the trace and epoch surfaces. |
| 17 | Tooling | Use Xray, Story, and pair tools as views over the same runtime. |
| 18 | Frames | Isolate app instances for tests, stories, tools, and SSR. |
| 19 | Routing | Make URLs part of the event/data loop. |
| 20 | Server side | Run the same model on the server. |
| 21 | Runtime model | Know which state is yours and which state is framework-managed. |
| 22 | Adapters | Use Reagent, UIx, and Helix without changing the app model. |
| 23 | Privacy and large data | Keep secrets and giant blobs out of traces. |
| 24 | Configuration and safety | Tune runtime policy without scattering switches. |
| 25 | From re-frame v1 | Translate old reflexes into v2's stricter shape. |
| 26 | Operating well | Keep the codebase boring in the good way. |
