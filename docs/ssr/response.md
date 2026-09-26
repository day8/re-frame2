# Controlling the response — `:rf.server/*`

You know the [request lifecycle](concepts.md#a-request-start-to-finish). This page is
one job: **shape status, headers, cookies, and redirects as data** from handlers.

Server-only [effects](../core/glossary.md#effect) write a per-request accumulator; the
Ring adapter materialises it. Response logic stays pure and testable like any
[effect map](../core/glossary.md#effect-map).

## The effects

| fx-id | args | does |
|---|---|---|
| `:rf.server/set-status` | `<int>` | set the HTTP status (last write wins; a conflict emits a `:rf.warning/multiple-status-set` trace) |
| `:rf.server/set-header` | `{:name :value}` | set a header, **replacing** any prior value (case-insensitive name) |
| `:rf.server/append-header` | `{:name :value}` | **append** another instance — for multi-value headers (`Set-Cookie`, `Vary`) |
| `:rf.server/set-cookie` | a structured cookie map | the adapter does the wire encoding |
| `:rf.server/delete-cookie` | `{:name :path :domain}` | expire a cookie (sugar over `set-cookie` with an empty `:value` and `:max-age 0`); `:path` and `:domain` are optional and must match the ones the cookie was set with |
| `:rf.server/redirect` | `{:status :location}` | short-circuit the render with a redirect (`:status` defaults to `302`; use `303` for POST success) |
| `:rf.server/safe-redirect` | `{:location :relative-only? :allow :status}` | a validated redirect for user-supplied locations — the open-redirect guard (`:status` defaults to `302`) |

All seven run on the server only, and all seven check their arguments in every
build, not only in a development build: a wrong type — a non-map args map, a
`:status` outside `100`–`599`, a non-string header `:value` — throws
`:rf.error/server-fx-args-invalid`, naming the offending key.

## Cookies are structured maps

Never hand-build header strings. Hand the framework the attributes; the adapter does
RFC 6265 wire encoding (the place raw-string cookie APIs grow quoting bugs):

```clojure
{:fx [[:rf.server/set-cookie
       {:name      "session"
        :value     session-token
        :max-age   3600
        :secure    true
        :http-only true
        :same-site :lax            ;; one of :strict :lax :none
        :path      "/"}]]}
```

The other two attributes are `:domain` and `:expires`. `:expires` is a point in
time as **epoch milliseconds** (a long, such as `1767225600000`); the
Ring adapter writes the HTTP date and throws `:rf.error/cookie-invalid-expires` on
anything that is not an integer. `:max-age`, in seconds, is usually simpler. With
neither, the cookie lasts for the browser session.

## Redirects truncate the render

If a redirect fires anywhere in the drain — setup, route handler, pipeline — the
runtime sets `:redirect`, **skips HTML render** (no body), and skips the hydration
payload (no client to hydrate). The host emits status + `Location` with no body.
Last-write-wins on multiple redirects, with a `:rf.warning/multiple-redirects` trace.

`:rf.server/redirect` **trusts its caller** — fine for a location you control.

!!! warning "Use `:rf.server/safe-redirect` for user-supplied locations"

    For a `:location` built from user input (`?next=...`), use `:rf.server/safe-redirect`.
    It runs the gauntlet in order: the URL must parse
    (`:rf.error/safe-redirect-invalid-url`); any scheme other than `http` or `https` —
    `javascript:`, `data:`, `mailto:`, `ftp:` and the rest — is rejected
    (`:rf.error/safe-redirect-scheme-rejected`); an `http(s)` URL with no host, such as
    `http:evil.example.com`, is rejected as invalid; and `:relative-only? true` or an
    `:allow ["app.example.com"]` allowlist gates the host
    (`:rf.error/safe-redirect-host-disallowed`). A relative location such as
    `/dashboard` always passes `:allow`. An attacker-controlled `?next=…` cannot
    bounce a freshly-authed user off-origin.

    A rejection does not throw. It reports the error and writes no redirect, so the
    request renders the page it would have rendered anyway, under its current status.

## A status the framework writes for you: the entry-denial `403`

Everything above is a status *your* handler writes. The framework writes on your
behalf in two situations, and they differ in kind:

- Something **throws** mid-drain. The [error projector](concepts.md#when-the-server-throws)
  maps the internal trace to a public error and stamps that error's `:status` —
  `404` for a routing miss, `400` for bad client input, `500` otherwise by default,
  or whatever your own projector decides.
- A route **refuses entry**. The runtime stamps `403`.

Only the first is an error. A refusal throws nothing, projects nothing, and
classifies as nothing — it is the app working correctly. So the `403` below is the
framework's **route-refusal floor**, not a projected status, and it is what the
rest of this section is about.

When a route's [`:can-enter`](../routing/concepts.md#guarding-entry--can-enter) guard rejects on a
server frame, the runtime writes `:status 403` to the accumulator **before**
`:rf.route/entry-denied` is dispatched. It is an ordinary `:rf.server/set-status`
write, so it plays by the rules on this page — including last-write-wins and the
`:rf.warning/multiple-status-set` trace. Think of it as a floor, not a decision:
denial has an HTTP meaning even when your app says nothing, and the framework
ships that meaning rather than leaving each adapter to guess.

The denial handler drains *before* the render step, so you have two ways to
supersede it:

- **`[:rf.server/redirect {:location "/login"}]`** — the login bounce.
  [Redirect precedence](#redirects-truncate-the-render) truncates the HTML *and*
  replaces the status.
- **`[:rf.server/set-status 404]`** — or any other status, winning by last-write.
  Use `404` when you would rather hide that the route exists at all.

Rendering the login route inside the same server frame is *not* one of them: the
response stays `403` unless the handler also writes one of the two.

!!! info "An unreplaced denial produces no data for the denied target"

    With the framework's no-op default handler — or any handler that establishes
    neither a redirect nor another status — the protected route stays
    **uncommitted**. The host renders your ordinary application shell under `403`.
    No `:on-match` runs, no route resource plan is built or awaited, and **no
    resource or hydration data for the denied target is produced**. There is
    nothing to leak because nothing was activated.

The floor is server-only — a client frame has no response to stamp. The routing
contract itself (what makes a guard reject, what the denial value carries, the
client-side recipe) is [`:can-enter`](../routing/how-to/require-sign-in-on-a-route.md)'s;
`403` is what this page adds to it.

## Header injection fails loud

A `\r` or `\n` smuggled into a header value is a response-splitting attack. The
framework does **not** quietly strip it:

- `:rf.server/set-header` / `:append-header` throw `:rf.error/header-invalid-value`
  on CR/LF/NUL in `:value`, and `:rf.error/header-invalid-name` on a `:name` outside
  the RFC 7230 token grammar (empty, whitespace, separators such as `:` or `;`)
- `:rf.server/redirect` throws `:rf.error/redirect-invalid-location` on CRLF/NUL in
  `:location`
- `:rf.server/set-cookie` and `:delete-cookie` hold `:name` to the RFC 6265 token
  grammar (`:rf.error/cookie-invalid-name`) and check *every* other attribute
  (`:value`, `:domain`, `:path`, …) for CR/LF/NUL before serialisation
  (`:rf.error/cookie-invalid-attribute`, whose `:attribute` slot names the field)

Fail-fast over strip-and-warn — silent normalisation masks the bug.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| Status set twice in one drain | Last write wins; `:rf.warning/multiple-status-set` | One intentional status, or accept last-write |
| A protected page renders the shell under `403` and you didn't set it | The framework's [entry-denial floor](#a-status-the-framework-writes-for-you-the-entry-denial-403) | Intended. Emit `:rf.server/redirect` or an explicit `:rf.server/set-status` from `:rf.route/entry-denied` |
| Multiple redirects | Last write wins; `:rf.warning/multiple-redirects` | One intentional redirect |
| CR/LF in a header value | `:rf.error/header-invalid-value` | Sanitize before `:set-header` / `:append-header` |
| Header name rejected | `:rf.error/header-invalid-name` | Use a plain token name such as `"X-Request-Id"` — no spaces, colons or keywords |
| CR/LF/NUL in redirect location | `:rf.error/redirect-invalid-location` | Don't pass raw user input to `:redirect` |
| Redirect written with `:url` or `:to` | `:rf.error/redirect-retired-target-key` | The target key is `:location` |
| Cookie name rejected | `:rf.error/cookie-invalid-name` | A string token name, e.g. `"session"` |
| CR/LF/NUL in a cookie attribute | `:rf.error/cookie-invalid-attribute` (`:attribute` names the field) | Structured cookie map only; don't pass raw input into `:path` / `:domain` |
| String or date `:expires` | `:rf.error/cookie-invalid-expires` when the Ring adapter writes the header | Epoch milliseconds as a long, or use `:max-age` |
| Wrongly typed fx argument (string status, `nil` header value, non-map args) | `:rf.error/server-fx-args-invalid`, naming the `:key` — in every build | Fix the args at the dispatch site |
| User-supplied `?next=` open redirect | `:rf.error/safe-redirect-invalid-url` / `-scheme-rejected` / `-host-disallowed` | Use `:rf.server/safe-redirect` with `:relative-only?` or `:allow` |

## Where this fits

- Form POST success → `[:rf.server/redirect {:status 303 :location …}]` — see
  [The model → two patterns](concepts.md#two-patterns-in-brief)
- Server throws / 4xx / 5xx → [When the server throws](concepts.md#when-the-server-throws)
- Route refuses entry → the [`403` floor](#a-status-the-framework-writes-for-you-the-entry-denial-403),
  and [Require sign-in on a route](../routing/how-to/require-sign-in-on-a-route.md)
  for the guard itself
- Full arg schemas → [re-frame.ssr](../api/re-frame.ssr.md) / [re-frame.ssr.ring](../api/re-frame.ssr.ring.md)
