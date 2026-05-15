# rf2-zk08x — JVM canned HTTP stub registration policy

## Scope

Report-only security/spec review for `implementation/http`, canned managed-HTTP stub fx IDs, JVM debug gating, specs/docs/tests, and SSR implications.

Primary question: should `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` ever be available in JVM production / SSR?

## Current Behavior

`implementation/http/src/re_frame/http_managed.cljc` registers four fx IDs at namespace load:

- `:rf.http/managed`
- `:rf.http/managed-abort`
- `:rf.http/managed-canned-success`
- `:rf.http/managed-canned-failure`

The two real managed-HTTP fxs are always registered. The two canned stub fxs are registered inside:

```clojure
(when interop/debug-enabled?
  ...)
```

On CLJS, `re-frame.interop/debug-enabled?` is `goog.DEBUG`, so `:advanced` + `goog.DEBUG=false` can DCE the whole branch. This is pinned by `implementation/scripts/check-elision.cjs`, which greps production bundles for both canned stub string fragments.

On JVM, `re-frame.interop/debug-enabled?` is read once at namespace load from `-Dre-frame.debug` or `RE_FRAME_DEBUG`. It defaults `true`; recognised false-y values disable it. Current JVM tests assert both sides:

- With the gate forced false and `re-frame.http-managed` reloaded, canned stub fxs are not registered while `:rf.http/managed` and `:rf.http/managed-abort` remain registered.
- With the default gate true, both canned stub fxs are registered.

The docs/spec currently describe the stubs as test-only. Spec 014 says the framework ships canonical stub fxs and later states these are "test-only surfaces". `implementation/SECURITY.md` documents the JVM dev flag as an SSR / long-running JVM production switch, but does not yet settle whether the canned stub IDs are acceptable under production SSR when the flag is accidentally or deliberately left true.

SSR tests currently use local app-specific stub fx IDs like `:http/get.canned` under per-frame `:fx-overrides`; they do not need the managed canned IDs to be globally registered in SSR.

## Risk

The current posture is safer than the original always-on JVM behavior, but still too broad for production SSR because the default JVM gate is `true`. Any JVM process that loads `re-frame.http-managed` without explicitly setting `-Dre-frame.debug=false` or `RE_FRAME_DEBUG=false` registers canned reply generators.

Risk shape:

- Canned HTTP fxs are not observability-only; they synthesize application replies. If reachable in a production frame, they can bypass real network transport, auth headers, partner calls, and failure behavior.
- SSR is a long-running server process facing untrusted request input and app-defined request boot handlers. A test/dev stub registered globally is a bad ambient authority in that process.
- The danger is mostly confused-deputy / misconfiguration / generated-code drift rather than a direct remote exploit. A leaked `:fx-overrides` map, story/test fixture path, dynamic event bridge, AI-generated handler, or operator staging config can silently turn production SSR data loads into canned success/failure.
- Debug gating alone couples unrelated authority classes. Trace/dev enrichment and "synthesize fake HTTP reply" are both dev-oriented, but the latter changes application behavior. It deserves a narrower opt-in.

## Answer

Canned HTTP stub fx IDs should not be acceptable in JVM production or production SSR. Production should be able to load `day8/re-frame2-http` and use `:rf.http/managed` without registering either canned stub ID.

They are acceptable only in explicit test/dev harnesses, including:

- Unit/integration tests using `:fx-overrides`.
- Story/testbed/dev SSR scenarios where the operator has opted into stub registration.
- Local REPL workflows that intentionally load test-support helpers.

They should not be considered part of the production managed-HTTP API.

## Options

### Option A — Keep `debug-enabled?` as the only gate

Pros:

- Already implemented.
- Preserves current test ergonomics.
- Aligns CLJS production bundles with existing DCE checks.

Cons:

- JVM default is true, so production safety depends on operator opt-out.
- Production posture is implicit and easy to miss.
- Stub authority remains coupled to trace/dev enrichment authority.

Verdict: insufficient for production SSR as a final policy.

### Option B — Require JVM production to set `re-frame.debug=false`

Pros:

- Minimal implementation.
- Matches existing `implementation/SECURITY.md` production-gate text.

Cons:

- Still an opt-out model for a behavior-changing test surface.
- A missing JVM flag registers stubs in production.
- Tests can pass while real deployment remains misconfigured.

Verdict: acceptable as a short-term mitigation, not as the spec posture.

### Option C — Add a dedicated canned-stub opt-in gate

Register canned IDs only when both conditions hold:

- Dev/debug mode is enabled.
- A stub-specific opt-in is true, e.g. `-Dre-frame.http.canned-stubs=true`, `RE_FRAME_HTTP_CANNED_STUBS=true`, or a build/profile-controlled constant.

Pros:

- Makes behavior-changing stub authority explicit.
- Preserves simple local/test opt-in.
- Lets production run with debug tooling in a controlled internal environment without accidentally enabling HTTP stubs.

Cons:

- Adds another config knob.
- Still loads stub registration code in the HTTP namespace unless carefully factored.

Verdict: viable, but not the cleanest long-term shape.

### Option D — Move canned registrations to a test-support namespace / artefact path

Keep `:rf.http/managed` and `:rf.http/managed-abort` in `re-frame.http-managed`. Move global canned fx registration to a namespace such as `re-frame.http-managed-test-support` or `re-frame.http-stubs`, included by test/dev aliases and deliberately required by story/testbed code.

`with-managed-request-stubs` can preserve ergonomics by registering a scoped/private target like `:rf.http/managed-test-stub` only around the test body, or by loading the test-support namespace explicitly.

Pros:

- Default-safe: production loading of `re-frame.http-managed` does not register canned IDs.
- Clear authority boundary: importing test support is the opt-in.
- Avoids coupling stubs to trace/debug state.
- SSR tests can continue using per-frame/app-specific `:fx-overrides` without production pollution.

Cons:

- Requires spec/docs migration from "framework ships two canonical stub fxs at normal HTTP namespace load" to "test-support namespace ships them".
- Some tests/examples using direct `:rf.http/managed-canned-*` overrides need one extra require or should switch to `with-managed-request-stubs`.

Verdict: recommended.

## Recommendation

Adopt Option D, optionally with Option C as a transitional belt-and-braces gate.

Normative policy:

- `:rf.http/managed` and `:rf.http/managed-abort` are production-eligible.
- `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` are test/dev-only and must not be registered by loading the production HTTP namespace.
- JVM production / SSR must not expose canned stub IDs by default.
- If global canned IDs remain available at all, they require explicit test-support import or a stub-specific opt-in. `debug-enabled?` alone is not sufficient.

Recommended implementation shape:

- Keep canned handler functions internal or move them to a test-support namespace.
- Move canned `fx/reg-fx` calls out of `re-frame.http-managed`.
- Provide `re-frame.http-test-support/install-canned-stub-fxs!` or an equivalent namespace-load registration for tests.
- Preserve `with-managed-request-stubs` as the preferred ergonomic API; it should register a temporary override target and uninstall it in `finally`.
- Keep CLJS production bundle sentinels, but update them to assert the test-support namespace is absent from production bundles rather than relying only on `goog.DEBUG`.

## Test Implications

Production posture tests should prove absence by default:

- Fresh JVM process or namespace reload with production HTTP namespace loaded and no test-support namespace: `registrar/lookup :fx :rf.http/managed` and `:rf.http/managed-abort` are present; both canned stub IDs are absent.
- Same assertion under `-Dre-frame.debug=false` / `RE_FRAME_DEBUG=false`, proving the production gate still works.
- If a debug-true JVM process loads only production HTTP, canned IDs still remain absent. This is the important new regression guard.
- Loading the explicit test-support namespace or enabling the stub-specific opt-in registers canned IDs, proving the developer/test path still works.
- `with-managed-request-stubs` continues to pass without requiring production canned IDs to be globally present.

SSR-specific tests should prove the request-server posture:

- `ssr-ring/ssr-handler` with `day8/re-frame2-http` on the classpath does not register canned managed IDs by default.
- Existing SSR tests continue to stub via frame-local `:fx-overrides` using app/test-owned IDs like `:http/get.canned`, or via explicit test-support import.
- A production-like SSR test should create a per-request frame, issue a managed HTTP effect, and assert no fallback to canned reply is possible unless explicit overrides/test support are configured.

CLJS tests should keep the current production-elision grep:

- `rf.http/managed-canned-success` and `rf.http/managed-canned-failure` must not appear in production bundles.
- Add a positive dev/test control so the grep remains non-vacuous.
- Bundle-isolation should assert no HTTP test-support namespace or canned registration strings leak into no-feature production bundles.

## Spec / Docs Implications

Spec 014 should be tightened:

- Replace "the framework ships two canonical stub fxs" with "the test-support surface provides canonical stub fxs/helpers".
- State explicitly that canned stub fx IDs are not production-eligible and must not be registered by default in JVM production / SSR.
- Prefer `with-managed-request-stubs` and per-dispatch/per-frame `:fx-overrides` as the canonical testing pattern.

Spec 009 / Security should clarify that `debug-enabled?` gates trace/dev enrichment, but behavior-changing test doubles may require narrower opt-in gates.

`implementation/SECURITY.md` should update "JVM-vs-CLJS stub semantics" to name the chosen stub gate and remove any language implying debug-true JVM production use may expose canned stubs.

Guide docs should separate:

- Managed HTTP production API.
- Managed HTTP test-support API.
- SSR/staging examples that use app-owned stub IDs under explicit `:fx-overrides`.

## Follow-up Beads Needed

Suggested follow-ups:

- `security(impl): move managed HTTP canned stub registrations behind explicit test-support gate`
- `test(http): add JVM production-default absence tests for managed canned stub fx IDs`
- `test(ssr): assert production SSR does not expose managed canned HTTP stub fx IDs by default`
- `spec(http): clarify canned managed-HTTP stubs are test-support only, not production API`
- `docs(security): document dedicated stub opt-in / test-support import policy`

No follow-up beads were filed from this report-only session.

## Bottom Line

No, canned managed-HTTP stub fx IDs should not be acceptable in JVM production / production SSR. The current `debug-enabled?` gate is useful but not sufficient because JVM defaults debug true and because fake-HTTP behavior is stronger authority than trace enrichment. The durable posture is explicit test-support import or a stub-specific opt-in, with tests proving production HTTP and SSR load paths register only the real managed fxs by default while preserving `with-managed-request-stubs` and scoped `:fx-overrides` ergonomics.
