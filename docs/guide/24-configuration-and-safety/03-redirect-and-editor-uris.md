# 24.03 — Redirects and editor URIs

## TL;DR

Two URI-shaped surfaces, two scheme policies. The framework's editor-launch links — the click-to-source affordance that opens your IDE at a specific line — reject three known-bad schemes (`javascript:`, `data:`, `vbscript:`) and, at the tool's click-time boundary, only honour a positive allowlist of editor schemes. `:rf.server/redirect` rejects CRLF in the `Location:` value and surfaces a structural URL check. Both defenses are small, and both are about closing the gap between "a string the framework will hand to the browser" and "a string the user controls."

The previous page covered the headers-and-cookies CRLF check on the server. This page covers the two scheme-checked surfaces — `:rf.server/redirect` (where the Location header is itself a URI) and the editor-URI templates that pair-tools use to open IDE links from in-page click affordances.

## `:rf.server/redirect` and `:rf.error/redirect-invalid-location`

A redirect from your SSR handler is a `Location:` header plus an HTTP 3xx status:

```clojure
;; common case — internal redirect after sign-in
{:fx [[:rf.server/redirect 302 "/dashboard"]]}

;; with a query string
{:fx [[:rf.server/redirect 302 (str "/login?return=" return-url)]]}

;; conditional — redirect to a partner domain
{:fx [[:rf.server/redirect 302 (str "https://" partner-domain "/oauth/return")]]}
```

The framework's `:rf.server/redirect` fx-handler runs two checks on the Location value:

1. **CRLF fail-fast** — same check as every other `:rf.server/*` value (see [§02 HTTP safety](02-http-safety.md)). A value containing `\r` or `\n` surfaces `:rf.error/redirect-invalid-location` and the redirect is not written.

2. **Structural URL shape** — the value must parse as a URL or as a relative path. A value that doesn't parse fails under the same `:rf.error/redirect-invalid-location` category. This catches malformed inputs (`"https//missing-colon"`, `"foo bar"` with embedded spaces, etc.) before the response goes out.

The error category is unified by design: the caller doesn't need to discriminate "CRLF in my URL" from "this isn't a URL" — both are "the redirect target you handed me isn't acceptable," and the fix is the same in both cases. The error event's `:tags` carry the failing value (subject to the `:sensitive?` redaction from [chapter 23a](../23a-privacy-secrets.md) if the value rides under a sensitive slot), so your trace stream surfaces the bug at its source.

`:rf.server/redirect` is also where the **safe-redirect-fx** pattern lives. The recommended shape for "redirect to a URL the user supplied" — say, the OAuth-return URL passed through a query string — is:

```clojure
;; In your event handler:
(rf/reg-event-fx :auth/post-login-redirect
  (fn [{:keys [db]} _]
    (let [requested-return (-> db :request :query-params :return)
          ;; Validate against your allowlist BEFORE building the fx.
          target           (if (allowed-return-url? requested-return)
                             requested-return
                             "/dashboard")]
      {:fx [[:rf.server/redirect 302 target]]})))
```

The framework's CRLF + URL-shape check catches the *malformed* and the *injecting* cases. Validating against an **allowlist** of legitimate return URLs is your responsibility — the framework doesn't and can't know which return URLs are yours. The pattern is: validate at the event handler, hand the fx a value that's *already* known-good, let the framework's fail-fast catch any contract violation if you've made a mistake. Defense in depth.

See [Security.md §CRLF injection at HTTP-response boundaries](../../../spec/Security.md#crlf-injection-at-http-response-boundaries) for the normative description.

## Editor URI templates — the scheme allowlist

This one's about the dev-tools surface, not user-facing HTTP. When [chapter 15](../../causa/)'s click-to-source affordance opens your editor at a file:line, it builds a URI string and hands it to the browser:

```
vscode://file/path/to/foo.cljs:42:7
cursor://file/path/to/foo.cljs:42:7
idea://open?file=path/to/foo.cljs&line=42&column=7
```

Each tool that surfaces source-coords (Story, Causa, re-frame2-pair-mcp) lets you pick your editor at boot:

```clojure
{:rf.story/editor :vscode}     ;; one of :vscode / :cursor / :windsurf / :zed / :idea
{:rf.story/editor {:custom "vim://open?path={path}&line={line}"}}
```

The five named editors (`:vscode`, `:cursor`, `:windsurf`, `:zed`, `:idea`) are built-ins; the framework's builders for those schemes can only emit a known-good URI. The interesting surface is the `{:custom "..."}` form — the open-ended template that lets you point at any editor (Sublime's `subl:`, Emacs's `org-protocol:`, JetBrains Fleet's `fleet:`, your bespoke `my-editor://`, …) without waiting for an upstream PR.

That template, though, is a string the user wrote. If it's `{:custom "javascript:alert(1)"}`, clicking the source-coord link would run script in your dev tab. That's the threat; the defense is two-layered.

### Layer 1 — the three-scheme reject

The framework's URI builder refuses to emit a URI whose leading scheme is one of three known-bad schemes:

| Scheme | Why it's rejected |
|---|---|
| `javascript:` | Runs arbitrary JavaScript in the current origin |
| `data:` | Can carry inline HTML/script the browser renders |
| `vbscript:` | Legacy IE script scheme; still honoured in some embedded WebView surfaces |

If a `{:custom ...}` template's leading scheme matches any of these (case-insensitive, leading-whitespace-tolerant), the builder returns `nil`. The UI layer's `(when uri ...)` wrapper hides the link rather than rendering a no-op chip — visible failure, no silent rendering of a dangerous string.

The reject is **case-insensitive** and **whitespace-tolerant** — `" JavaScript:..."` and `"DATA:..."` still trip the gate. The attacker template that prepends whitespace hoping `triml` will strip it before the browser parses doesn't get through.

### Layer 2 — the click-time positive allowlist

At the **click-time** boundary, each tool layers a positive allowlist on top of the three-scheme reject. The allowlist is `editor-uri/allowed-editor-uri-schemes` and covers the canonical editor schemes:

```
vscode, vscode-insiders, cursor, windsurf, zed,
idea, jetbrains, fleet,
subl, emacs, emacsclient, org-protocol,
vim, nvim, mvim,
txmt, atom, file
```

A `{:custom ...}` template that resolves to `http://...` or `gopher://...` would otherwise navigate the tab rather than launch an editor — surprising to the dev who's expecting "this opens my IDE." The allowlist makes that an obvious no-op rather than a silent surprise.

The two layers are deliberately redundant. The three-scheme reject closes the script-execution attack surface — that's the must-have. The positive allowlist closes the "navigate-where-you-didn't-mean-to" footgun — that's the would-be-nice. A tool that accidentally drops one layer still has the other.

Both predicates live in `re-frame.source-coords.editor-uri` and are exported for tooling reuse: `editor-uri` (the builder) and `allowed-uri?` (the click-time gate). If you write your own dev tool that surfaces source-coords, use both.

See [Security.md §Editor URI scheme allowlist](../../../spec/Security.md) for the full rationale (rf2-vwcsq + rf2-cm93v / rf2-p887o for the positive allowlist).

## What you don't have to validate

The framework's stance — same as the previous page — is that the *fx-handler* (for redirects) and the *URI builder* (for editor templates) are the right sites for input validation, not your code at every call site. If you pass `:rf.server/redirect` a value that contains CRLF, the fx-handler surfaces the bug. If you write a `{:custom "javascript:..."}` template into your editor config, the builder returns `nil` and the UI hides the link.

What you *do* have to handle is the allowlist of return URLs — the framework can't know which targets are yours. Everything else, the framework's check sites have you covered.

## Cross-references

- [§02 — HTTP safety primitives](02-http-safety.md) — the broader CRLF fail-fast story; `:rf.server/redirect` inherits its CRLF check from there.
- [Chapter 11 — The server side](../11-server-side.md) — `:rf.server/*` fx narrative.
- [Chapter 15 — Tooling](../../causa/) — where click-to-source surfaces live (Story, Causa, re-frame2-pair-mcp).
- [Security.md §Editor URI scheme allowlist](../../../spec/Security.md) — the normative description (and the rf2-vwcsq + rf2-cm93v decisions).
- [Security.md §CRLF injection at HTTP-response boundaries](../../../spec/Security.md#crlf-injection-at-http-response-boundaries) — the redirect's CRLF check site.
