# re-frame.ssr.head

Build a page's `<head>` (title, meta tags, links, scripts, JSON-LD) from app state on the server, so crawlers and link unfurlers that do not run JavaScript see it in the first response. A head model is data derived from `app-db`, with the keys `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs` and `:body-attrs`. The Ring handler in [`re-frame.ssr.ring`](re-frame.ssr.ring.md) resolves and emits the head for each page.

Ships in the `day8/re-frame2-ssr` artefact, alongside [`re-frame.ssr`](re-frame.ssr.md).

```clojure
(:require [re-frame.core     :as rf]
          [re-frame.ssr.head :as head])
```

```clojure
(rf/reg-head :head/article
  (fn [db {:keys [params]}]
    (let [{:keys [title summary]} (get-in db [:articles (:id params)])]
      {:title (str title " — Example")
       :meta  [{:name "description" :content summary}]})))

;; A route selects it with {:head :head/article} in its metadata.
;; For a request frame on that route:
(head/head-model->html (head/head-model :app/request-17) {:wrap? true})
;; => "<head><title>Hello — Example</title>…</head>"
```

Register heads with `rf/reg-head` on the `re-frame.core` facade; its entry is on [`re-frame.ssr`](re-frame.ssr.md#reg-head). `head-model` and `head-model->html` are also re-exported on `re-frame.ssr` as `ssr/head-model` and `ssr/head-model->html`, and each pair is the same function. There is no `:rf/head` subscription: `:rf/head-model` names a data shape, and `head-model` returns the model without recording it anywhere. [Head metadata](../ssr/head.md) shows a complete head and route.

Every key of a head model is optional:

```clojure
{:title      "Hello — Example"                                   ;; <title>; "" emits none
 :meta       [{:name "description" :content "…"}                 ;; one <meta> per map, in order
              {:property "og:title" :content "Hello"}]
 :link       [{:rel "canonical" :href "https://example.com/a/1"}] ;; one <link> per map
 :script     [{:src "/js/widget.js"}]                              ;; one <script> per map, attributes only
 :json-ld    [{"@context" "https://schema.org"                     ;; one application/ld+json <script> per map
               "@type"    "Article"
               "headline" "Hello"}]
 :html-attrs {:lang "en"}                                          ;; attributes for <html>, written by the host shell
 :body-attrs {:class "article"}}                                   ;; attributes for <body>, written by the host shell
```

`:meta`, `:link`, `:script` and `:json-ld` are vectors, even for one entry. A `:script` entry has no body, so inline script content cannot go through the head model. Text and attribute values are escaped for you, and every `<` in JSON-LD strings is written as `\u003c`, so a value cannot close its element.

## Reading a head

### `head-model`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model frame-id) → :rf/head-model
  (head-model frame-id {:head-id id :route route}) → :rf/head-model
  ```
- **Description**: Returns `frame-id`'s head model. Pure and JVM-runnable. It resolves in one pass:
    1. The effective route is `:route` when the key is present (an explicit `{:route nil}` means no route), else the frame's active route slice from `runtime-db` at `[:rf.runtime/routing :current]`.
    2. The head is `:head-id` when supplied, else the effective route's `:head` metadata, else [`default-head`](#default-head).
    3. The head fn runs against that same effective route, so `{:route r}` with no `:head-id` previews `r` end to end.
    - `frame-id` is required.
    - The frame selects the registrations as well as the data, so a head declared in one image cannot run against another image's `app-db`.
    - The Ring handler calls it for every page and does not let the head fail the request. If resolution throws, `:rf.error/no-such-head` or a throwing head fn alike, the page renders with an empty head and the handler emits the always-on `:rf.error/ssr-head-resolution-failed` record; the status stays as it was.
- **Errors**:
    - `:rf.error/no-such-head` — the selected head id (`:head-id`, or the route's `:head`) is not registered. A route that declares no `:head` falls back to [`default-head`](#default-head) without error.
    - `:rf.error/no-frame-context` — `frame-id` is `nil`; the head never resolves against a default frame.
- **Example**:
  ```clojure
  (head/head-model :app/request-17)
  ;; => {:title "Hello — Example" :meta [{:name "description" :content "…"}]}

  (head/head-model :app/request-17 {:head-id :head/article})
  ```

## Emitting a head

### `head-model->html`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model->html head-model)               → HTML string
  (head-model->html head-model {:wrap? bool}) → HTML string
  ```
- **Description**: Renders a head model to its inner-`<head>` HTML fragment. The SSR pipeline calls it for you; call it directly when you emit your own HTML envelope.
    - Output order is fixed: `<title>`, then `<meta>` in declaration order, then `<link>`, then `<script>`, then JSON-LD.
    - `:wrap?` (default `false`) wraps the fragment in `<head>…</head>`.
    - `:html-attrs` and `:body-attrs` are not emitted: they belong to `<html>` and `<body>`, which the host shell writes.
- **Errors**:
    - `:rf.error/ssr-invalid-attribute-name` — a `:meta`, `:link` or `:script` attribute key outside the HTML5 attribute-name grammar.
    - `:rf.error/invalid-json-ld-number` (JVM) — a non-finite number (`##Inf`, `##-Inf`, `##NaN`) in `:json-ld`, which JSON cannot represent.
    - `:rf.error/invalid-json-ld-key` (JVM) — a `nil` map key in `:json-ld`.
- **Example**:
  ```clojure
  (head/head-model->html (head/head-model :app/request-17) {:wrap? true})
  ;; => "<head><title>Hello — Example</title>…</head>"
  ```

## Framework integration

Not for application code — used by adapters, tools and the test harness.

### `default-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-head frame-id) → :rf/head-model
  ```
- **Description**: Returns the model `head-model` falls back to when the effective route declares no `:head`, or there is no route. It carries `:title` (the frame's `:doc`, or `""` when it has none) and the viewport `<meta>`.
    - It carries no `<meta charset>`: the host shell writes the charset, and a head model carrying one too would emit the tag twice.
    - A host reads it only when reimplementing the default flow.
- **Example**:
  ```clojure
  (head/default-head :app/request-17)
  ;; => {:title "…" :meta [{:name "viewport" :content "width=device-width, initial-scale=1"}]}
  ```

## See also

- [`re-frame.ssr`](re-frame.ssr.md) — rendering, hydration, error projection, and the `rf/reg-head` entry.
- [`re-frame.ssr.ring`](re-frame.ssr.ring.md) — the Ring handler that resolves and emits the head for each request.
- [`re-frame.routing`](re-frame.routing.md) — routes select a head with `:head` metadata.
- [Head metadata](../ssr/head.md) — the guide, including the separate `:rf/head-hash` channel.
