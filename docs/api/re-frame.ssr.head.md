# re-frame.ssr.head

The `<head>` half of the SSR contract. A head-model is data derived from `app-db` — `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs`, `:body-attrs` — never an imperative DOM API, because the server-rendered HTML must carry that metadata on first byte: crawlers and link-unfurlers do not run JS.

Ships in `day8/re-frame2-ssr` alongside [`re-frame.ssr`](re-frame.ssr.md). This is where the head surface is defined; require the namespace directly:

```clojure
(:require [re-frame.ssr.head :as head])
```

The **registrar** is the exception: `reg-head` rides the `re-frame.core` facade as `rf/reg-head` (see [`re-frame.core`](re-frame.core.md)), because registration macros capture call-site source-coords and stay central. The **read** `head-model` and the **serialiser** `head-model->html` are both re-exported on [`re-frame.ssr`](re-frame.ssr.md) as `ssr/head-model` and `ssr/head-model->html`, so the whole read side sits beside `render-to-string`; each pair of names is one function.

There is **no `:rf/head` subscription** — `:rf/head-model` names a data shape, not a registry entry. Reading a head is a pure read: `head-model` RETURNS the model and records it nowhere.

## Reading a head

### `head-model`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model frame-id) → :rf/head-model
  (head-model frame-id {:head-id id :route route}) → :rf/head-model
  ```
- **Description**: Resolve `frame-id`'s head model and return it. Pure and JVM-runnable. One read answers the whole question, and it resolves in one pass:
    1. The **effective route** is `:route` when the key is present — an explicit `{:route nil}` means *no route* — else the frame's active route slice from the runtime-db at `[:rf.runtime/routing :current]`.
    2. The **head** is `:head-id` when supplied, else the effective route's `:head` metadata, else [`default-head`](#default-head). A selected-but-unregistered id raises `:rf.error/no-such-head`; a route declaring no `:head` at all falls back silently.
    3. The head fn is evaluated against that **same** effective route, so `{:route r}` with no `:head-id` previews `r` end to end.

    `frame-id` is **carried, not ambient** (EP-0002): the no-arg form was removed and a `nil` frame raises `:rf.error/no-frame-context` rather than resolving against a synthesised `:rf/default`. The carried frame selects the REGISTRATIONS as well as the data, so a head declared in one image cannot run against another image's `app-db`. Also available as `ssr/head-model` on [`re-frame.ssr`](re-frame.ssr.md); the two names are the same function.
- **Example**:
  ```clojure
  (head/head-model :app/request-17)
  ;; => {:title "Hello — Example" :meta [{:name "description" :content "…"}]}

  (head/head-model :app/request-17 {:head-id :head/article})
  ```

### `default-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (default-head frame-id) → :rf/head-model
  ```
- **Description**: The fallback model `head-model` returns when the effective route declares no `:head` (or there is no route). Carries `:title` — the frame's `:doc`, or `""` when it has none — plus the viewport `<meta>`. It deliberately carries no `<meta charset>`: charset is an envelope concern the host shell stamps, and a head model carrying one too would emit the tag twice. Plumbing a host reads only when reimplementing the default flow.
- **Example**:
  ```clojure
  (head/default-head :app/request-17)
  ;; => {:title "…" :meta [{:name "viewport" :content "width=device-width, initial-scale=1"}]}
  ```

## Emitting a head

### `head-model->html`

- **Kind**: function
- **Signature**:
  ```clojure
  (head-model->html head-model)
  (head-model->html head-model {:wrap? bool})
  ```
- **Description**: Render a `:rf/head-model` to its inner-head HTML fragment in canonical order — `<title>`, then `<meta>` in declaration order, then `<link>`, then `<script>`, then JSON-LD. `:wrap?` (default `false`) wraps the fragment in `<head>…</head>`. `:html-attrs` and `:body-attrs` are deliberately NOT emitted: they belong to `<html>` and `<body>`, which the host shell stamps. The SSR pipeline calls this internally; reach for it directly only when emitting a custom HTML envelope. Also available as `ssr/head-model->html` on [`re-frame.ssr`](re-frame.ssr.md).
- **Example**:
  ```clojure
  (head/head-model->html (head/head-model :app/request-17) {:wrap? true})
  ;; => "<head><title>Hello — Example</title>…</head>"
  ```

## See also

- [`re-frame.ssr`](re-frame.ssr.md) — the render, hash, hydration and error-projection surface, and the `ssr/head-model` / `ssr/head-model->html` re-exports.
- [`re-frame.core`](re-frame.core.md) — the `rf/reg-head` registration macro.
- [Head metadata](../ssr/head.md) — the recipe, including the separate `:rf/head-hash` channel.
