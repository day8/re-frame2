# Retired App Composition Vocabulary

Status: draft finding.

## Crowding Signal

EP-0023 establishes `image -> frame -> event stream` as the public model.
Anything that presents the older app/realm/module composition vocabulary as a
current API is now drift. This is the largest cleanup finding because it is not
just duplicate spelling; it is two mental models on the same facade.

This document uses the retired terms only to identify API and code that should
be removed or renamed. It does not treat them as legitimate current vocabulary.

Current retired public spellings still visible in `re-frame.core`:

- `module`
- `app`
- `install!`
- `reinstall!`
- `realm`
- `dispose-realm!`
- `realm-ids`
- `installed-app`
- `app-registrations`
- `app-owns`
- `app-requires`
- `frame-realm`
- `migration-map`
- `migration-explain`
- `assert-process-local-frame-id!`

Current public model:

- `image`: selected registration set, provenance, and capability facts;
- `make-frame` / `reg-frame`: create a live execution context from images;
- `reload-images!`: change a frame's image composition while preserving frame
  memory;
- `destroy-frame!`: end the frame lifecycle.

Implementation/spec evidence:

- `docs/EP/EP-0023-image-loaded-frames.md:6-25` states that
  `image -> frame -> event stream` is the public model and that EP-0013 is
  superseded at the public app surface.
- `docs/EP/EP-0023-image-loaded-frames.md:201-208` explicitly says to avoid
  `app`, `application`, `realm`, and `module` as conceptual nouns in the model.
- `implementation/core/src/re_frame/core.cljc:1916-2338` still exposes the
  retired construction, install, and inspection names on the facade.
- `spec/API.md:516-532` still rows the retired model as current public API.
  That spec/API text is itself part of the cleanup target.

## Observed Use Cases

1. Normal examples register through `reg-*`, create frames, and mount providers.
   They do not build app values or install them.

2. Image/frame examples and tools use `rf/image`, `make-frame`, `reg-frame`,
   `frame-provider`, and `reload-images!` as the public composition path.

3. Test isolation uses `make-frame`, `with-new-frame`, and `destroy-frame!`.

4. Story and Xray need separate behavior/state contexts and are naturally
   image/frame clients.

5. The retired construction names appear in implementation conformance tests
   and legacy substrate tests, not in normal examples.

6. Two read names, `installed-app` and `realm-ids`, have live Xray usage around
   the old module/realm panel. That is a tooling migration target, not evidence
   that the vocabulary belongs on the public facade.

7. `reload-images!` has little adoption yet, but it is the right public
   hot-reload verb for the new model, not a retired synonym.

## Proposed Cleanup

Remove the retired app/realm/module construction vocabulary from
`re-frame.core` and current public API docs:

```clojure
;; Retire from the public facade
rf/module
rf/app
rf/install!
rf/reinstall!
rf/realm
rf/dispose-realm!
rf/app-registrations
rf/app-owns
rf/app-requires
rf/installed-app
rf/realm-ids
rf/frame-realm
```

The public composition story becomes:

```clojure
(def app-image
  (rf/image {:include-ns ["my.app.**"]}))

(def frame
  (rf/make-frame {:id :app/main
                  :images [app-image]
                  :initial-db {}}))

(rf/reload-images! :app/main [app-image])
(rf/destroy-frame! frame)
```

Tooling that still reads legacy transitional structures is a migration target.
Until that migration is complete, the access should sit behind a clearly
internal namespace; the durable direction is to inspect images, resolved image
generations, and frames. It should not keep the retired vocabulary alive on the
app-author front porch.

Remove or quarantine migration shims once no longer needed:

- `migration-map`
- `migration-explain`
- `assert-process-local-frame-id!`

These are transitional diagnostics, not durable public API.

## Why This Is Better

The old names and the current names answer the same question: "what behavior
does this isolated running thing use?" EP-0023 chose the smaller answer. An
image is pure data; a frame is the live fold; the event stream is what runs.
That is enough.

Keeping the retired vocabulary visible makes the API archaeological. A user or
agent reading `re-frame.core` sees two complete composition systems and must
guess which one is alive. A good Clojure API does not ask the reader to perform
history recovery before writing code.

This cleanup is also the most direct way to honor "one name per fact". The fact
is not an app installed into a realm. The public fact is an image loaded into a
frame.
