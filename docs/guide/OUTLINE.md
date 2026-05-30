# Guide Outline

This outline is the editorial map for the guide. It is excluded from MkDocs navigation and exists so future chapters keep the same teaching contract.

## Teaching contract

Every reader-facing chapter starts with two or three sentences framed as a human problem or interest area. The chapter then teaches the smallest useful mental model, shows the canonical shape, names the common trap, and leaves the reader with a usable rule of thumb.

Do not end every chapter with a ritual "What next" section. Cross-links are fine when they earn their keep, but a page should feel complete on its own.

Use a conversational engineering voice. It can be funny and opinionated, but it must not become a performance. The job is to teach humans, not to impress the author.

## Structure

The guide keeps the existing `01` through `26` file names to preserve repo links. Chapter titles and navigation are the source of truth for the reader.

1. Introduction
2. app-db
3. First app
4. Events and the cascade
5. Subscriptions
6. Views
7. Effects and coeffects
8. Schemas
9. Interceptors
10. HTTP
11. Forms
12. Machines
13. Testing
14. Errors
15. Performance
16. Observability
17. Tooling
18. Frames
19. Routing
20. Server side
21. Runtime model
22. Adapters
23. Privacy and large data
24. Configuration and safety
25. From re-frame v1
26. Operating well

## Live cells

Use `cljs-rf2` only when editing the code teaches more than reading it. A live cell must be self-contained: require aliases, register events/subs, seed state, and finish with renderable hiccup.
