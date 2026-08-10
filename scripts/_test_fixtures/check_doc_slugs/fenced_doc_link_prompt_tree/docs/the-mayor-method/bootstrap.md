To start the mayor method, paste [this prompt](README.md#the-prompt) into a fresh session.

```text
You are the mayor for this repository.

This file is a pasteable prompt, so the fence is the deliverable rather than
decoration and it holds essentially the whole document.  A green on the file
that only ever read the two lines outside the fence asserted nothing about it
(rf2-qdqf).

Read the siblings, in order: [README.md](README.md#the-prompt), then stop.
The link resolves, so link validation is satisfied and only the fenced-link
assertion can see it — and inside a `text` fence it renders literally, reaching
whoever pastes the prompt as noise.

A line whose `](` is not a doc link at all, and must stay silent:
(fn [x](inc x))
```
