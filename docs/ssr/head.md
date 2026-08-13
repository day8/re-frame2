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
- **No `:head` is fine.** Default: `<title>` from frame metadata, plus `charset` and
  `viewport`.
- **Body and head hashes are separate channels.** The body render-tree hash rides
  `:rf/render-hash` ([when the renders disagree](concepts.md#when-the-renders-disagree));
  a reconstructible head emits a *separate*, optional `:rf/head-hash` (stamped
  `data-rf-head-hash` on `<head>`), omitted when the head can't be recomputed — an
  explicit `:head` string, or a degraded head. The bundled runtime compares only the
  body hash; it ships **no** automatic head comparison. The head *model* is
  reconstructible, so a host that wants the check recomputes `(rf/active-head
  frame-id)` from the hydrated app-db + route slice and compares it to `:rf/head-hash`
  itself — that wiring is the host's, not automatic.
- **Keeping the document head current is the app's job.** There is no DOM-head
  reconciler in v1. The first byte carries the server-rendered head; refreshing
  `<title>` / `<meta>` on an SPA route change needs an app- or host-level head
  manager.

!!! warning "JSON-LD escaping is handled for you"

    String values inlined into `<script type="application/ld+json">` re-encode every
    `<` so an attacker-supplied title cannot close the script tag. You write data; the
    emitter applies the position-correct escape at every leaf.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| `reg-head` throws at first call | `:rf.error/ssr-artefact-missing` | Require `re-frame.ssr` |
| Path put in route metadata | `:rf.error/route-bad-metadata` — throws at registration, so the route never registers | Path is the **third** positional arg of `reg-route`, not a metadata key |
| SPA route change leaves stale `<title>` | No automatic DOM-head reconciler in v1 | App- or host-level head manager after hydrate |
| Expecting automatic head-hash compare | Runtime compares body `:rf/render-hash` only | Host recomputes `(rf/active-head frame-id)` vs `:rf/head-hash` if wanted |

## See also

- [Routing concepts](../routing/concepts.md) — route metadata including `:head`
- [API: reg-head](../api/re-frame.ssr.md) / head accessors on `re-frame.core`
