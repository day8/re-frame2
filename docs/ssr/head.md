# Head metadata — title, meta, OpenGraph, JSON-LD

You know [server render + hydrate](concepts.md). This page is one job: put
**`<title>`, `<meta>`, OpenGraph, and JSON-LD** on the first byte as pure data from
app-db — not an imperative DOM API. Crawlers don't run JS.

**Prerequisites.** [The model](concepts.md) and a [route](../routing/concepts.md) that
can name a head id.

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
       :link    [{:rel "canonical" :href (routing/route-url :route/article params)}]
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
- **Mismatch detector covers the head.** The head rides the same render-tree hash as
  the body ([When the renders disagree](concepts.md#when-the-renders-disagree)). On
  the client, the head recomputes from the hydrated app-db + route slice — SPA route
  changes keep `<title>` / `<meta>` current.

!!! warning "JSON-LD escaping is handled for you"

    String values inlined into `<script type="application/ld+json">` re-encode every
    `<` so an attacker-supplied title cannot close the script tag. You write data; the
    emitter applies the position-correct escape at every leaf.

## See also

- [Routing concepts](../routing/concepts.md) — route metadata including `:head`
- [API: reg-head](../api/re-frame.ssr.md) / head accessors on `re-frame.core`
