# re-frame.ssr.head

The `<head>` half of the SSR contract. A head-model is data derived from `app-db` — `:title`, `:meta`, `:link`, `:script`, `:json-ld`, `:html-attrs`, `:body-attrs` — never an imperative DOM API, because the server-rendered HTML must carry that metadata on first byte: crawlers and link-unfurlers do not run JS.

Ships in `day8/re-frame2-ssr` alongside [`re-frame.ssr`](re-frame.ssr.md). The two **reads** below live here and nowhere else — require the namespace directly:

```clojure
(:require [re-frame.ssr.head :as head])
```

The **registrar** is the exception: `reg-head` rides the `re-frame.core` facade as `rf/reg-head` (see [`re-frame.core`](re-frame.core.md)), because registration macros capture call-site source-coords and stay central. And the **serialiser** `head-model->html` is re-exported on [`re-frame.ssr`](re-frame.ssr.md#head-model-html) as `ssr/head-model->html`, so the emission half sits beside `render-to-string`; both names are the same function.

There is **no `:rf/head` subscription** — `:rf/head-model` names a data shape, not a registry entry. Reading a head is a pure read: the fns below RETURN the model and record it nowhere.

## Reading a head

### `render-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (render-head head-id {:frame frame-id})
  (render-head head-id {:frame frame-id :route route})
  ```
- **Description**: Evaluate the head-fn registered under `head-id` against the frame's `app-db` and a route, returning the produced `:rf/head-model`. Pure and JVM-runnable. `:route` is optional — omitted, it reads the frame's active route slice from the runtime-db at `[:rf.runtime/routing :current]`. `:frame` is **carried, not ambient** (EP-0002): an absent frame raises `:rf.error/no-frame-context`, and an unregistered `head-id` raises `:rf.error/no-such-head`. The carried frame selects the REGISTRATIONS as well as the data, so a head declared in one image cannot run against another image's `app-db`.
- **Example**:
  ```clojure
  (head/render-head :head/article {:frame :app/request-17})
  ;; => {:title "Hello — Example" :meta [{:name "description" :content "…"}]}
  ```

### `active-head`

- **Kind**: function
- **Signature**:
  ```clojure
  (active-head frame-id) → :rf/head-model
  ```
- **Description**: Sugar over `render-head` — looks up the active route's `:head` metadata for the named frame, resolves it to a registered head id, calls `render-head`, and returns the model. With no `:head` on the route (or no active route) it returns the default head. **1-arity only**: the no-arg form was removed under EP-0002, and a `nil` `frame-id` raises `:rf.error/no-frame-context` rather than resolving against a synthesised `:rf/default`.
- **Example**:
  ```clojure
  (head/active-head :app/request-17)
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
  (head/head-model->html (head/active-head :app/request-17) {:wrap? true})
  ;; => "<head><title>Hello — Example</title>…</head>"
  ```

## See also

- [`re-frame.ssr`](re-frame.ssr.md) — the render, hash, hydration and error-projection surface, and the `ssr/head-model->html` re-export.
- [`re-frame.core`](re-frame.core.md) — the `rf/reg-head` registration macro.
- [Head metadata](../ssr/head.md) — the recipe, including the separate `:rf/head-hash` channel.
