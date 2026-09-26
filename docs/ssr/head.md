# Head metadata — title, meta, OpenGraph, JSON-LD

Crawlers and link unfurlers read the `<title>`, `<meta>` tags and JSON-LD in the first
response; most of them run no JavaScript. In re-frame2 you register a head function
that derives that metadata from app-db, and a [route](../routing/concepts.md) names
which head to use. The Ring handler resolves it for each request and writes it into
the page's `<head>`.

For the articles app, an article page gets its title, description and OpenGraph tags
from the article it shows:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.routing :as rf.routing]   ;; route-url; also loads reg-route
          [re-frame.ssr :as ssr])              ;; loads reg-head

(rf/reg-head :head/article
  {:doc "Article page head: title, description and OpenGraph from the article."}
  (fn [db {:keys [params] :as _route}]
    (let [{:keys [title summary image]}
          (some #(when (= (:id params) (:id %)) %) (:articles db))]
      {:title   (str title " — Articles")
       :meta    [{:name "viewport" :content "width=device-width, initial-scale=1"}
                 {:name "description" :content summary}
                 {:property "og:title" :content title}
                 {:property "og:image" :content image}]
       :link    [{:rel "stylesheet" :href "/css/app.css"}
                 {:rel "canonical" :href (rf.routing/route-url {:to     :articles/show
                                                                :params params})}]
       :json-ld [{"@context" "https://schema.org"
                  "@type"    "Article"
                  "headline" title}]})))

(rf/reg-route :articles/show
  {:params [:map [:id :string]]
   :head   :head/article}      ;; which head this route uses
  "/articles/:id")             ;; the path is the third argument, not a metadata key
```

Once `:rf/server-init` hands the URL to routing, as in [Reading the
request](concepts.md#reading-the-request), a request for `/articles/1` renders a
`<head>` holding the title, the `<meta>` tags, the stylesheet and canonical `<link>`s,
and a `<script type="application/ld+json">`. A `nil` attribute value, such as a missing
`:summary`, omits that attribute.

The head function has the shape of a [subscription](../core/glossary.md#subscription):
it takes app-db and the matched route, and returns a head model. It must be pure. The
server calls it once, after the drain has settled app-db, so it sees the same state
the body renders from.

## How the head is built

- **Every key is optional.** A head model can carry `:title`, `:meta`, `:link`,
  `:script`, `:json-ld`, `:html-attrs` and `:body-attrs`. `:meta`, `:link`, `:script`
  and `:json-ld` are vectors, one map per tag. `:html-attrs` and `:body-attrs` become
  attributes on `<html>` and `<body>`. [re-frame.ssr.head](../api/re-frame.ssr.head.md)
  has the full model.
- **Output order is fixed**: `<title>`, then `<meta>`, `<link>`, `<script>`, and
  JSON-LD last.
- **Values are escaped for you.** Text and attribute values are HTML-escaped, and every
  `<` in a JSON-LD string is re-encoded, so an attacker-supplied article title cannot
  close the script tag.
- **`:script` entries are attributes only**, such as `{:src "/js/widget.js"}`. A head
  model cannot carry an inline script body.
- **One head per route.** Heads do not compose from parent to child routes; routes
  that want the same metadata name the same head id.
- **The page shell writes `<meta charset="utf-8">` itself**, so never put a charset in
  a head model.

A route with no `:head` gets a default head: a `<title>` from the frame's `:doc`
(none when it has no `:doc`) and the `viewport` meta. `ssr-handler`'s per-request frame
has no `:doc`, so under the handler the default head has no `<title>`; register a head
to get one. A head you register replaces the default entirely, which is why the example
above includes the `viewport` meta itself.

## After hydration

The server writes the head once, into the first response. There is no DOM-head
reconciler: when the client navigates to another route, nothing updates `<title>` or
`<meta>`. If your app needs the document head to follow client-side navigation, add an
app- or host-level head manager that reads the same head model with
[`ssr/head-model`](../api/re-frame.ssr.head.md#head-model).

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| `reg-head` throws at first call | `:rf.error/ssr-artefact-missing` | Require `re-frame.ssr` |
| Page renders with an empty `<head>` (no title, no meta) but a normal status | `:rf.error/ssr-head-resolution-failed`: the head fn threw, or the route names an unregistered head. The handler renders the page with an empty head rather than failing it | Read the record's `:exception`; fix the head fn, or register the head the route names |
| `ssr/head-model` throws when you call it directly | `:rf.error/no-such-head`: the `:head-id` or the route's `:head` names nothing registered | Register the head, or fix the id |
| The route never registers | `:rf.error/route-bad-metadata`, thrown at registration: the path was put in the metadata map | The path is the third positional argument of `reg-route` |
| `<title>` goes stale after a client-side route change | No DOM-head reconciler | Add an app- or host-level head manager ([After hydration](#after-hydration)) |
| A head mismatch between server and client is never reported | The runtime compares only the body's `:rf/render-hash` | Compare `:rf/head-hash` yourself ([The head hash](#the-head-hash)) |

## Advanced

### Custom shells

Two `ssr-handler` options change where the head comes from:

- **A `:head` string replaces the resolved head entirely**: route head, default
  `<title>` and `viewport` meta alike. It is injected unescaped, so use it only for a
  static app without routing and never build it from untrusted input. It also drops
  the `:rf/head-hash` channel, since there is no model to recompute.
- **A custom `:html-shell` receives the resolved head as HTML** in its `opts` map
  under `:head`; put it inside your `<head>`. If you assemble the document yourself
  outside the handler, `(ssr/head-model->html (ssr/head-model frame-id))` gives the
  same fragment.

[The page shell](concepts.md#the-page-shell) covers the other shell options, and
[`ssr-handler`](../api/re-frame.ssr.ring.md#ssr-handler) lists them all.

### The head hash

The body and the head are hashed separately. The body's render-tree hash travels as
`:rf/render-hash`, and the client compares it on hydration ([When the renders
disagree](concepts.md#when-the-renders-disagree)). A head built from a model also
emits an optional `:rf/head-hash`, stamped as `data-rf-head-hash` on `<head>`. It is
omitted when the head cannot be recomputed: an explicit `:head` string, or a head that
failed to resolve.

The bundled runtime does not compare the head hash. A host that wants the check
recomputes `(ssr/render-tree-hash (ssr/head-model frame-id))` from the hydrated app-db
and route, and compares it with `:rf/head-hash` itself.
