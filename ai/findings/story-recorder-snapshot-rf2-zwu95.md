# Story recorder/snapshot browser slice - rf2-zwu95

## Current status

Story now has a passing browser-test slice for the recorder/playback,
redaction, assertion diagnostics, snapshot identity, share/QR URL
integrity, decorator isolation, and frame-isolation scenarios covered
by rf2-zwu95. The narrow JVM share-url test and the Story feature-load
browser gate both pass locally.

## Scenarios landed

- Recorder captures three canvas user actions against `:story.counter/empty`;
  the generated snippet contains exactly three `[:counter/inc]` events and the
  replay-authored `:story.counter/clicked-three-times` variant produces the
  same visible count of 3.
- Recorder redacts a real sensitive sign-in dispatch from
  `:story.counter-matrix/recorder-redaction`, producing `[:rf/redacted]`
  and not leaking `browser-secret`, `redaction@example.com`, or
  `:auth/sign-in`.
- Test mode assertion failures expose actionable structured detail: expected,
  actual, and reason are visible after expanding the failing row.
- Snapshot identity is surfaced on the canvas and remains stable across a
  reload for the same variant, args, modes, and substrate.
- Share/QR URL generation preserves the variant id, active toolbar mode,
  selected args, and `#/stories` route by inserting query params before the
  hash fragment.
- Decorator wrapping is isolated across variants: the variant-level decorator
  appears on `:story.counter/clicked-three-times` and does not leak onto
  `:story.counter/loaded`.
- Frame isolation is exercised by mutating isolation A, mutating isolation B,
  and returning to A; A returns to its own initial frame state rather than
  inheriting B's mutation.

## Support fixes landed

- Canvas runtime runs are keyed by variant, mode, overrides, substrate, and
  hot-reload tick. Ordinary app-db rerenders no longer replay static
  `:events` and reset user interactions.
- The hot-reload poll now updates its fingerprint baseline for newly allocated
  or removed frames without bumping `:hot-reload-tick`. Only existing-frame
  fingerprint drift forces a rerun.
- The canvas frame provider subtree is keyed by variant id so React does not
  reuse the previous variant's frame provider during a fast variant switch.
- Story panels honor their optional `:for` targets against the focused variant
  or its parent story, preventing project-specific panels from reading
  unrelated frame state.
- CLJS snapshot hashes are formatted as unsigned 32-bit hex strings, matching
  the JVM expectation used by tests.
- Share UI gained stable data-test hooks so the browser harness can assert the
  URL and QR surface without role ambiguity.

## Missing or intentionally not forced

- Share URL hydration from a pasted link was not implemented. This slice
  verifies generation integrity only.
- The expensive static-build, production-elision, bundle-size, and release-tier
  checks were not run locally; the changed surface is covered by the
  Story feature-load PR gate and the narrow JVM URL test.
- Recorder persistence/save-to-source remains outside this slice. The browser
  test stops at generated snippet review because no write surface is required
  for rf2-zwu95.

## Overlap avoided

Noether is working under rf2-dczqg. During rebase, upstream already had a
recorder redaction fixture under `:story.counter-matrix/recorder-redaction`;
this slice adopted that fixture instead of adding a second redaction story.
The remaining work stayed on the narrower recorder/assertion/snapshot/share/
decorator/frame-isolation path. I did not touch broad Story authoring flows,
catalog design, general docs generation, or non-recorder Story UX beyond the
minimal test hooks and substrate fixes needed to make these scenarios
deterministic.

## Actionable insights

- The default Story feature-load port 8031 is easy to collide with across
  parallel worktrees. Agents should use a unique `STORY_FEATURE_LOAD_PORT`
  during local pre-checkin, and CI should allocate ports per job.
- New-frame fingerprint baseline updates should not be treated as hot-reload
  drift. Re-running a just-selected variant can replay fixture events during a
  user recording and produce false recorder output.
- Story panel `:for` scoping is load-bearing for testbed isolation. A panel
  written for one story can crash another story if it assumes a frame shape and
  is rendered globally.
- Snapshot identity tests should assert browser-visible unsigned hex, not just
  JVM hashes, because CLJS signed integer formatting can silently diverge.
