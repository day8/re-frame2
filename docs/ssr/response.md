# Controlling the response — `:rf.server/*`

A server-rendered page needs more than HTML: a status, cache headers, cookies, and
sometimes a redirect instead of a page. In re-frame2 an event handler returns these as
[effects](../core/glossary.md#effect), and the Ring adapter turns them into the HTTP
response.

This extends the tutorial's boot event from
[Step 8](tutorial.md#step-8--shape-the-response). The retired `/posts` URL redirects,
and every other request renders the article list with a cache header and a cookie that
remembers the page size:

```clojure
(rf/reg-event :rf/server-init
  {:platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [db rf.server/request]} _]
    (if (= "/posts" (:uri request))
      {:fx [[:rf.server/redirect {:status 301 :location "/articles"}]]}
      (let [limit (or (some-> (get-in request [:query-params "limit"]) parse-long) 10)]
        {:db (assoc db :articles/limit limit)
         :fx [[:dispatch [:articles/seed (vec (take limit sample-articles))]]
              [:rf.server/set-header {:name "Cache-Control" :value "public, max-age=60"}]
              [:rf.server/set-cookie {:name    "limit"
                                      :value   (str limit)
                                      :max-age 86400
                                      :path    "/"}]]}))))
```

`curl -i localhost:3000/posts` returns a `301` with `Location: /articles` and no body.
`curl -i localhost:3000/` returns the page with `Cache-Control` and `Set-Cookie`
headers. The effects write a per-request response accumulator, which `ssr-handler`
reads after the drain. Your event handler only returns data, so you can test it like
any other [effect map](../core/glossary.md#effect-map).

## The effects

| fx-id | args | does |
|---|---|---|
| `:rf.server/set-status` | `<int>` | set the HTTP status |
| `:rf.server/set-header` | `{:name :value}` | set a header, replacing any earlier value (names are case-insensitive) |
| `:rf.server/append-header` | `{:name :value}` | add another instance of a multi-value header such as `Vary` |
| `:rf.server/set-cookie` | a cookie map | set a cookie; the adapter writes the `Set-Cookie` header |
| `:rf.server/delete-cookie` | `{:name :path :domain}` | expire a cookie (a `set-cookie` with an empty `:value` and `:max-age 0`); `:path` and `:domain` are optional and must match the ones the cookie was set with |
| `:rf.server/redirect` | `{:status :location}` | answer with a redirect instead of the page (`:status` defaults to `302`) |
| `:rf.server/safe-redirect` | `{:location :relative-only? :allow :status}` | a redirect to a location built from user input, validated first (`:status` defaults to `302`) |

All seven run on the server only. When the client dispatches the same event after
hydration, it skips them, so one handler serves both sides.

If two writes in one drain set the status, the last one wins and a
`:rf.warning/multiple-status-set` trace fires. Redirects work the same way, with
`:rf.warning/multiple-redirects`.

[re-frame.ssr](../api/re-frame.ssr.md) has the full argument schemas.

## Cookies

Pass the cookie's attributes as a map. The adapter writes the RFC 6265 encoding, so
you never build a `Set-Cookie` string by hand:

```clojure
[:rf.server/set-cookie
 {:name      "session"
  :value     session-token
  :max-age   3600
  :secure    true
  :http-only true
  :same-site :lax            ;; one of :strict :lax :none
  :path      "/"}]
```

The other two attributes are `:domain` and `:expires`. `:expires` is epoch
milliseconds as a long, such as `1767225600000`; the Ring adapter writes the HTTP date.
`:max-age`, in seconds, is usually simpler. With neither, the cookie lasts for the
browser session.

## Redirects

A redirect from anywhere in the drain (`:rf/server-init`, a route handler, any event
they dispatch) replaces the page. The handler renders no HTML and ships no hydration
payload; the response is the status and `Location` header with an empty body.

For a form POST that succeeded, answer `303` so the browser follows with a GET:
`[:rf.server/redirect {:status 303 :location "/articles"}]`.
[The form action](concepts.md#two-patterns-in-brief) shows the whole pattern.

`:rf.server/redirect` sends the browser wherever `:location` says, which is right for
a location your code chose. When the location comes from the request, such as
`?next=` after sign-in, use `:rf.server/safe-redirect`:

```clojure
[:rf.server/safe-redirect {:location       (get-in request [:query-params "next"])
                           :relative-only? true}]
```

It checks the location before redirecting:

- The URL must parse (`:rf.error/safe-redirect-invalid-url`). An `http` or `https`
  URL with no host, such as `http:evil.example.com`, fails here too.
- The scheme must be `http` or `https`. `javascript:`, `data:`, `mailto:` and the
  rest are rejected (`:rf.error/safe-redirect-scheme-rejected`).
- With `:relative-only? true`, only a relative location passes. With
  `:allow ["app.example.com"]`, an absolute URL must name a listed host, and a
  relative location such as `/dashboard` always passes. A failure here is
  `:rf.error/safe-redirect-host-disallowed`.

A rejected location does not throw. The runtime reports the error and writes no
redirect, so the request renders the page it would have rendered anyway, under its
current status.

## A status the framework writes for you: the entry-denial `403`

When a route's [`:can-enter`](../routing/concepts.md#guarding-entry--can-enter) guard
rejects on the server, the runtime writes `:status 403` before it dispatches
`:rf.route/entry-denied`. Denial has an HTTP meaning even when your app says nothing,
so the framework sets it rather than leaving each adapter to guess.

This is an ordinary `:rf.server/set-status` write, so last-write-wins applies. The
denial handler runs before the render, and can replace the `403` in two ways:

- `[:rf.server/redirect {:location "/login"}]` sends the visitor to sign in. The
  redirect replaces both the status and the page.
- `[:rf.server/set-status 404]`, or any other status, wins as the later write. Use
  `404` to hide that the route exists.

Rendering the login route inside the same server frame does neither: the response
stays `403` unless the handler also writes one of the two.

With the framework's default denial handler, which does nothing, or any handler that
writes neither a redirect nor a status, the host renders your application shell under
`403`. The protected route is never entered: no `:on-match` runs, no route resource is
fetched, and no resource or hydration data for it is produced.

A denial is not an error. Nothing throws, so the [error
projector](concepts.md#when-the-server-throws) is not involved. It is also server-only;
a client frame has no response to write. [Require sign-in on a
route](../routing/how-to/require-sign-in-on-a-route.md) covers the guard itself.

## Invalid values throw

The effects check their arguments in every build, not only in a development build. A
wrong type, such as a non-map args map, a `:status` outside `100`–`599` or a
non-string header `:value`, throws `:rf.error/server-fx-args-invalid` naming the
offending key.

A `\r` or `\n` in a header value would let an attacker split the response, so the
effects throw rather than strip the characters:

- `:rf.server/set-header` and `:append-header` throw `:rf.error/header-invalid-value`
  on CR, LF or NUL in `:value`, and `:rf.error/header-invalid-name` on a `:name`
  outside the RFC 7230 token grammar (empty, whitespace, separators such as `:` or
  `;`).
- `:rf.server/redirect` throws `:rf.error/redirect-invalid-location` on CR, LF or NUL
  in `:location`.
- `:rf.server/set-cookie` and `:delete-cookie` throw `:rf.error/cookie-invalid-name`
  on a `:name` outside the RFC 6265 token grammar, and
  `:rf.error/cookie-invalid-attribute` on CR, LF or NUL in any other attribute
  (`:value`, `:domain`, `:path`, …); its `:attribute` slot names the field. A
  non-integer `:expires` throws `:rf.error/cookie-invalid-expires` when the Ring
  adapter writes the header.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| A protected page renders the shell under `403` and you didn't set it | The framework's [entry-denial status](#a-status-the-framework-writes-for-you-the-entry-denial-403) | Intended. Emit `:rf.server/redirect` or `:rf.server/set-status` from `:rf.route/entry-denied` |
| Status set twice in one drain | Last write wins; `:rf.warning/multiple-status-set` | Write one status, or rely on the later write deliberately |
| Multiple redirects | Last write wins; `:rf.warning/multiple-redirects` | Write one redirect |
| `?next=` redirect is ignored and the page renders | `:rf.error/safe-redirect-invalid-url` / `-scheme-rejected` / `-host-disallowed` | The location failed validation; this is the guard working |
| Wrongly typed fx argument (string status, `nil` header value, non-map args) | `:rf.error/server-fx-args-invalid`, naming the `:key`, in every build | Fix the args at the dispatch site |
| CR/LF in a header value | `:rf.error/header-invalid-value` | Remove them before `:set-header` / `:append-header` |
| Header name rejected | `:rf.error/header-invalid-name` | Use a plain token name such as `"X-Request-Id"`: no spaces, colons or keywords |
| CR/LF/NUL in a redirect location | `:rf.error/redirect-invalid-location` | Don't pass raw user input to `:redirect`; use `:safe-redirect` |
| Redirect written with `:url` or `:to` | `:rf.error/redirect-retired-target-key` | The target key is `:location` |
| Cookie name rejected | `:rf.error/cookie-invalid-name` | A string token name, such as `"session"` |
| CR/LF/NUL in a cookie attribute | `:rf.error/cookie-invalid-attribute` (`:attribute` names the field) | Don't pass raw input into `:path` / `:domain` |
| String or date `:expires` | `:rf.error/cookie-invalid-expires` when the Ring adapter writes the header | Epoch milliseconds as a long, or use `:max-age` |
