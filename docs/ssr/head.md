# Head metadata — title, meta, OpenGraph, JSON-LD

You know [server render + hydrate](concepts.md). This page is one job: put
**`<title>`, `<meta>`, OpenGraph, and JSON-LD** on the first byte as pure data from
app-db — not an imperative DOM API. Crawlers don't run JS. A
[route](../routing/concepts.md) names the head.

## Register a head, name it on the route

`reg-head` is on the `rf/` facade. The route opts in via `:head` metadata (path is the
**third** positional argument — never a metadata key):

```clojure
(:require [re-frame.core :as rf]
          [re-frame.routing :as routing]   ;; route-url; also loads reg-route
          [re-frame.ssr])                  ;; reg-head artefact

(rf/reg-head :head/article
  {:doc "Article-page head — derives title/meta/og from the article."}
  (fn [db {:keys [params] :as _route}]
    (let [{:keys [title summary image]} (get-in db [:articles (:id params)])]
      {:title   (str title " — Example")
       :meta    [{:name "description" :content summary}
                 {:property "og:title" :content title}
                 {:property "og:image" :content image}]
       :link    [{:rel "canonical" :href (routing/route-url {:to :route/article :params params})}]
       :json-ld [{"@context" "https://schema.org"
                  "@type"    "Article"
                  "headline" title}]})))

(rf/reg-route :route/article
  {:params [:map [:id :string]]
   :head   :head/article}            ;; which head model to use
  "/articles/:id")                   ;; path is the third slot — not a metadata key
```

The head fn has the shape of a [sub](../core/glossary.md#subscription) —
`(db, route) → head-model`, pure, with any subs inside evaluating against static
app-db.

## Rules of thumb

- **Output order is canonical.** Emitter writes `<title>`, then `<meta>`, `<link>`,
  `<script>`, JSON-LD; `:html-attrs` / `:body-attrs` populate `<html>` / `<body>`.
- **One head per route, shared by id.** No parent/child composition in v1 — routes
  that want the same metadata name the same head id.
- **No `:head` is fine.** Default: `<title>` from the frame's `:doc` (none when it
  has no `:doc`) plus the `viewport` meta. `ssr-handler`'s per-request frame has no
  `:doc`, so under the handler the default head has no `<title>` — register a head
  to get one. The page shell always writes
  `<meta charset="utf-8">` itself, so never put a charset in a head model. A head you
  register replaces the default, so include the `viewport` meta in it if you want one.
- **`:script` entries are attributes only** (`{:src "/js/widget.js"}`).
  A head model cannot carry an inline script body.
- **Body and head hashes are separate channels.** The body render-tree hash rides
  `:rf/render-hash` ([when the renders disagree](concepts.md#when-the-renders-disagree));
  a reconstructible head emits a *separate*, optional `:rf/head-hash` (stamped
  `data-rf-head-hash` on `<head>`), omitted when the head can't be recomputed — an
  explicit `:head` string, or a degraded head. The bundled runtime compares only the
  body hash; it ships **no** automatic head comparison. The head *model* is
  reconstructible, so a host that wants the check recomputes
  `(ssr/render-tree-hash (ssr/head-model frame-id))` from the hydrated app-db + route
  slice and compares it to `:rf/head-hash` itself — that wiring is the host's, not
  automatic.
- **Keeping the document head current is the app's job.** There is no DOM-head
  reconciler in v1. The first byte carries the server-rendered head; refreshing
  `<title>` / `<meta>` on an SPA route change needs an app- or host-level head
  manager.

!!! warning "JSON-LD escaping is handled for you"

    String values inlined into `<script type="application/ld+json">` re-encode every
    `<` so an attacker-supplied title cannot close the script tag. You write data; the
    emitter applies the position-correct escape at every leaf.

## Stylesheets and custom shells

A stylesheet is a `:link` entry like any other, so it belongs in the head model:

```clojure
:link [{:rel "stylesheet" :href "/css/app.css"}
       {:rel "canonical"  :href canonical-url}]
```

Two handler options change where the head comes from, and both need care:

- **`ssr-handler`'s `:head` string replaces the resolved head entirely** — route
  head, default `<title>` and `viewport` meta alike — and is injected unescaped.
  Use it only for a static app without routing, and never build it from untrusted
  input. It also drops the `:rf/head-hash` channel, since there is no model to
  recompute.
- **A custom `:html-shell` receives the resolved head as HTML** in its `opts` map
  under `:head`; put it inside your `<head>`. If you assemble the document yourself
  outside the handler, `(ssr/head-model->html (ssr/head-model frame-id))` gives the
  same fragment.

[`ssr-handler`](../api/re-frame.ssr.ring.md#ssr-handler) lists the shell options, and
[`re-frame.ssr.head`](../api/re-frame.ssr.head.md) the full head model.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| `reg-head` throws at first call | `:rf.error/ssr-artefact-missing` | Require `re-frame.ssr` |
| Page renders with an empty `<head>` (no title, no meta) but a normal status | `:rf.error/ssr-head-resolution-failed` — the head fn threw, or the route names an unregistered head; the handler degrades to an empty head rather than failing the page | Read the record's `:exception`; fix the head fn, or register the head the route names |
| `ssr/head-model` throws when you call it directly | `:rf.error/no-such-head` — the `:head-id` or the route's `:head` names nothing registered | Register the head, or fix the id |
| Path put in route metadata | `:rf.error/route-bad-metadata` — throws at registration, so the route never registers | Path is the **third** positional arg of `reg-route`, not a metadata key |
| SPA route change leaves stale `<title>` | No automatic DOM-head reconciler in v1 | App- or host-level head manager after hydrate |
| Expecting automatic head-hash compare | Runtime compares body `:rf/render-hash` only | Host compares `(ssr/render-tree-hash (ssr/head-model frame-id))` with `:rf/head-hash` if wanted |

## See also

- [Routing concepts](../routing/concepts.md) — route metadata including `:head`
- [API: reg-head](../api/re-frame.ssr.md) — `reg-head` is on `re-frame.core`; `head-model` and `head-model->html` are on `re-frame.ssr`
