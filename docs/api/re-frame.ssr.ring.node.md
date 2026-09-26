# re-frame.ssr.ring.node

Render the page body on a Node sidecar instead of the JVM, for a view layer whose components are JavaScript, such as a Fresco root. `renderer` returns a value for [`ssr-handler`](re-frame.ssr.ring.md#ssr-handler)'s `:renderer`: for each request it sends the settled request frame's state to the Node render sidecar and takes back the body markup. The head, payload, shell, status, headers and error projection stay on the JVM. [Render on Node](../ssr/concepts.md#render-on-node) walks through the bundle, the sidecar and deployment.

Ships in the `day8/re-frame2-ssr-ring` artefact, beside [`re-frame.ssr.ring`](re-frame.ssr.ring.md). The render sidecar is not published; [Start the sidecar, and mind the skew](../ssr/concepts.md#5-start-the-sidecar-and-mind-the-skew) covers where it comes from and its flags.

```clojure
(:require [re-frame.ssr.ring      :as ssr.ring]
          [re-frame.ssr.ring.node :as node])
```

## The renderer

### `renderer`

- **Kind**: function
- **Signature**:
  ```clojure
  (renderer opts) → (fn [{:keys [frame-id request opts]}] {:body-html … :render-hash nil})
  ```
- **Description**: Returns a value for `ssr-handler`'s `:renderer`. It validates `opts` and builds one HTTP client at construction; per request it projects the frame's state under `:render-state`, POSTs it to `<endpoint>/render` and returns the sidecar's body verbatim.
    - `:render-hash` is always `nil`, so the page carries no `data-rf-render-hash` and no payload `:rf/render-hash`.
    - The projection applies the handler's `:payload-include-sensitive`, so the markup and the payload agree on a permitted value.
    - The render module reads the state back with `re-frame.ssr.render-state/deserialize`.
    - A request waits at most `:timeout-ms` + `:admission-ms` + 600 ms, body included.
- **Options**:
    - `:entry` (required) — the bundle entry to render; a non-empty string.
    - `:build-id` (required) — a non-empty string equal to the server bundle's build id.
    - `:render-state` (required) — what the render may see: `{:app-db [<keys>] :runtime-db [<keys>]}`, fail-closed allowlists of top-level keys with either slot optional, or `(fn [frame-id] → {:rf/app-db {…} :rf/runtime-db {…}})`. It is a policy separate from the handler's `:payload`. Every value must read back equal through EDN: no fn, record, `#inst`, `#uuid`, ratio, big or float number, or integer past 2^53 ([Two policies, and why they differ](../ssr/concepts.md#4-two-policies-and-why-they-differ)).
    - `:endpoint` — the sidecar's absolute `http` or `https` URL; default `"http://127.0.0.1:8148"`. A non-loopback URL is accepted; securing it is the operator's job.
    - `:args` — root arguments sent as EDN, under the same value rule.
    - `:timeout-ms` — the render deadline sent to the sidecar; a positive integer, default `1000`.
    - `:admission-ms` — how long the sidecar may queue the request; a non-negative integer, default `250`. Match the sidecar's `--admission-ms`.
- **Errors**:
    - At construction: `:rf.error/ssr-node-renderer-opt-invalid` (ex-data `:opt`, `:got`) for a malformed option; `:rf.error/ssr-missing-payload-policy` or `:rf.error/ssr-malformed-payload-allowlist`, with `:opt :render-state`, for a missing or malformed `:render-state`.
    - Per request, thrown at the render call. The handler projects each as `:rf.error/ssr-render-failed`, with the id below in its `:exception`, so the request answers the 5xx error page and `:on-error` is not called:
        - `:rf.error/ssr-node-unreachable` — no HTTP answer: connection refused, connect timeout, an I/O fault.
        - `:rf.error/ssr-node-deadline` — the render missed its deadline; `:observed-by` is `:sidecar` (its `504`) or `:jvm`.
        - `:rf.error/ssr-node-refused` — any other non-`200`; carries `:status` and the sidecar's `:refusal` code.
        - `:rf.error/ssr-node-build-skew` — a `200` whose `x-rf-ssr-build` header is missing or is not `:build-id`.
        - `:rf.error/ssr-render-state-invalid` — a projected value the EDN wire cannot carry, or a `:render-state` fn that returned a malformed envelope.
- **Example**:
  ```clojure
  (ssr.ring/ssr-handler
    {:initial-events [[:app/init]]
     :payload        [:todos]
     :renderer       (node/renderer
                       {:entry        "my-app/root"
                        :build-id     (System/getenv "MY_APP_BUILD_ID")
                        :render-state {:app-db     [:todos :session]
                                       :runtime-db [:rf.runtime/routing]}})})
  ```

## Defaults

`renderer` takes `:endpoint`, `:timeout-ms` and `:admission-ms` from these vars when you leave them out, and adds `wire-margin-ms` to derive its HTTP timeout.

| Var | Value | Meaning |
|---|---|---|
| `default-endpoint` | `"http://127.0.0.1:8148"` | The sidecar launcher's default bind, used when `:endpoint` is absent. |
| `default-timeout-ms` | `1000` | Used when `:timeout-ms` is absent. It matches the launcher's `--timeout-ms` default. |
| `default-admission-ms` | `250` | Used when `:admission-ms` is absent. It matches the launcher's `--admission-ms` default; raise both together. |
| `wire-margin-ms` | `500` | The fixed allowance the HTTP timeout adds for the wire itself. |

- `(http-timeout-ms opts) → ms` returns the HTTP timeout each request carries: `:timeout-ms` + `:admission-ms` + `wire-margin-ms`. Pass it `opts` with both keys present, as `renderer` merges them. The sidecar is given the smaller budget, so it refuses a late render before the JVM stops waiting.
- `(validate-opts! opts) → opts` runs the construction checks `renderer` runs and returns `opts` unchanged, throwing the construction errors listed under `renderer`. It does not merge the defaults itself, so pass it `opts` merged over them, as `renderer` does.

## See also

- [`re-frame.ssr.ring`](re-frame.ssr.ring.md) — the handler that takes this renderer as `:renderer`.
- [`re-frame.ssr`](re-frame.ssr.md) — rendering, the hydration payload and error projection.
- [Render on Node](../ssr/concepts.md#render-on-node) — the bundle, the sidecar and deployment.
