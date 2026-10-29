# re-frame.ssr.ring.node

Render the page body on a Node sidecar instead of the JVM, for a view layer whose components are JavaScript, such as a Fresco root. `renderer` returns a value for [`ssr-handler`](re-frame.ssr.ring.md#ssr-handler)'s `:renderer`: for each request it sends the settled request frame's state to the Node render sidecar and takes back the body markup. The head, payload, shell, status, headers and error projection stay on the JVM. [Render on Node](../ssr/concepts.md#render-on-node) walks through the bundle, the sidecar and deployment.

Ships in the `day8/re-frame2-ssr-ring` artefact, beside [`re-frame.ssr.ring`](re-frame.ssr.ring.md). That jar also carries the Node render sidecar, which `renderer` launches and supervises itself when given `:module`; [Run the sidecar, and mind the skew](../ssr/concepts.md#5-run-the-sidecar-and-mind-the-skew) covers that, and running it yourself.

```clojure
(:require [re-frame.core          :as rf]
          [re-frame.ssr           :as ssr]
          [re-frame.ssr.ring      :as ssr.ring]
          [re-frame.ssr.ring.node :as node])
```

Assuming the app's `:app/init` event is registered, `out/my-app-server/server.js` is its server bundle, and `MY_APP_BUILD_ID` names that bundle's build:

```clojure
(rf/init! ssr/adapter)

;; Launches the sidecar on the bundle, and fails here if it cannot.
;; `defonce`: each `renderer` under `:module` launches its own
;; sidecar, so a re-evaluated `def` would leave the last one running.
(defonce renderer
  (node/renderer
    {:module       "out/my-app-server/server.js"
     :entry        "my-app/root"
     :build-id     (System/getenv "MY_APP_BUILD_ID")
     :render-state {:app-db     [:todos :session]
                    :runtime-db [:rf.runtime/routing]}}))

(ssr.ring/ssr-handler
  {:frame    {:initial-events [[:app/init]]}
   :payload  [:todos]
   :renderer renderer})

;; …and when the system stops:
(.close renderer)
```

## The renderer

### `renderer`

- **Kind**: function
- **Signature**:
  ```clojure
  (renderer opts) → (fn [{:keys [frame-id request opts]}] {:body-html …})  ; also a java.io.Closeable
  ```
- **Description**: Returns a value for `ssr-handler`'s `:renderer`. It validates `opts` and builds one HTTP client at construction; per request it projects the frame's state under `:render-state`, POSTs it to the sidecar's `/render` and returns the sidecar's body verbatim.
    - With `:module`, construction also launches the sidecar: it copies the sidecar out of the jar to a temp directory, runs it with `node` on the bundle on a loopback port the OS picks, and waits for its ready line, refusing a bundle whose build id is not `:build-id`. It hands the sidecar `:timeout-ms` (as its deadline and its ceiling) and `:admission-ms`. A sidecar that exits is relaunched, after 100 ms doubling to 10 s while relaunches fail; requests that arrive while it is down fail as `:rf.error/ssr-node-unreachable`.
    - The returned fn is a `java.io.Closeable`. `.close` stops a sidecar it launched (SIGTERM, then a forced kill after 5 s) and is safe to call twice; a JVM shutdown hook stops one nobody closed. On a renderer built with `:endpoint` it does nothing.
    - With a map policy, the handler's `:payload` paths permit inside the render-state allowlist, so the markup and the payload agree on a permitted value.
    - The render module reads the state back with `re-frame.ssr.render-state/deserialize`. A Fresco bundle's module built by `re-frame.fresco.server/node-module` does that for you, so the bundle's `:exports-var` is one form ([Write the render module](../ssr/concepts.md#2-write-the-render-module)).
    - A request waits at most `:timeout-ms` + `:admission-ms` + 600 ms, body included.
- **Options**:
    - `:entry` (required) — the bundle entry to render; a non-empty string.
    - `:build-id` (required) — a non-empty string equal to the server bundle's build id.
    - `:render-state` (required) — what the render may see: `{:app-db [<keys>] :runtime-db [<keys>]}`, fail-closed allowlists of top-level keys with either slot optional, or `(fn [frame-id] → {:rf/app-db {…} :rf/runtime-db {…}})`. It is a policy separate from the handler's `:payload`. Every value must read back equal through EDN: no fn, record, `#inst`, `#uuid`, `Ratio`, `BigDecimal`, `BigInt`, `BigInteger`, `Float`, `##NaN`, or integer outside −(2^53 − 1) through 2^53 − 1, and no map or set holding two numbers the browser reads as one value (`1` beside `1.0`) ([Two policies, and why they differ](../ssr/concepts.md#4-two-policies-and-why-they-differ)).
        - The map must name `:app-db` or `:runtime-db` (or both). Each present allowlist is a sequential of keywords; vectors, lists and lazy sequences are accepted. An empty allowlist, or an omitted partition, sends that partition as `{}`. An allowlisted key absent from the live frame is omitted.
        - Other policy keys and sets are invalid. There is no whole-partition keyword policy.
        - A projector fn must return a map with only `:rf/app-db` and `:rf/runtime-db`; either may be omitted, but a present partition must be a map with keyword top-level keys. Its result is validated for the wire, but the fn owns selection and redaction: the automatic classification projection applies only to the map policy.
    - `:module` — the path of the server bundle, resolved against the JVM's working directory; it must name a file. The renderer launches the sidecar on it. Naming `:endpoint` as well is refused.
    - `:node` — with `:module`: the Node executable, default `"node"` from the JVM's `PATH`. It must be Node 24 or later.
    - `:isolates` — with `:module`: the sidecar's worker threads, a positive integer; default 2.
    - `:endpoint` — without `:module`: the absolute `http` or `https` URL of a sidecar you run; default `"http://127.0.0.1:8148"`. A non-loopback URL is accepted; securing it is the operator's job.
    - `:args` — root arguments sent as EDN, under the same value rule.
    - `:timeout-ms` — the render deadline sent to the sidecar; a positive integer, default `1000`.
    - `:admission-ms` — how long the sidecar may queue the request; a non-negative integer, default `250`. A sidecar launched with `:module` is given it; one you run needs the same `--admission-ms`.
- **Errors**:
    - At construction: `:rf.error/ssr-node-renderer-opt-invalid` (ex-data `:opt`, `:got`) for a malformed option, including `:module` beside `:endpoint` and `:node` or `:isolates` without `:module`; `:rf.error/ssr-missing-payload-policy` or `:rf.error/ssr-malformed-payload-allowlist`, with `:opt :render-state`, for a missing or malformed `:render-state`.
    - At construction with `:module`: `:rf.error/ssr-node-build-skew` (ex-data `:expected`, `:serving`) when the bundle names another build; `:rf.error/ssr-node-unreachable` (ex-data `:endpoint` nil, `:ex-class`, `:ex-message` carrying the sidecar's last stderr lines) when it cannot start — the bundle will not load, `node` is missing, or no ready line arrives within 30 s.
    - Per request, thrown at the render call. The handler projects each as `:rf.error/ssr-render-failed`, with the id below in its `:exception`, so the request answers the 5xx error page and `:on-error` is not called:
        - `:rf.error/ssr-node-unreachable` — no HTTP answer: connection refused, connect timeout, an I/O fault, or a launched sidecar that is down while it is relaunched (`:endpoint` nil).
        - `:rf.error/ssr-node-deadline` — the render missed its deadline; `:observed-by` is `:sidecar` (its `504`) or `:jvm`.
        - `:rf.error/ssr-node-refused` — any other non-`200`; carries `:status` and the sidecar's `:refusal` code.
        - `:rf.error/ssr-node-build-skew` — a `200` whose `x-rf-ssr-build` header is missing or is not `:build-id`.
        - `:rf.error/ssr-render-state-invalid` — a projected value the EDN wire cannot carry, or a `:render-state` fn that returned a malformed envelope.

## Defaults

`renderer` takes `:timeout-ms` and `:admission-ms` from these vars when you leave them out, and `:endpoint` too when there is no `:module`; it adds `wire-margin-ms` to derive its HTTP timeout.

| Var | Value | Meaning |
|---|---|---|
| `default-endpoint` | `"http://127.0.0.1:8148"` | The sidecar launcher's default bind, used when `:endpoint` is absent. |
| `default-timeout-ms` | `1000` | Used when `:timeout-ms` is absent. It matches the launcher's `--timeout-ms` default. |
| `default-admission-ms` | `250` | Used when `:admission-ms` is absent. It matches the launcher's `--admission-ms` default; for a sidecar you run, raise both together. |
| `wire-margin-ms` | `500` | The fixed allowance the HTTP timeout adds for the wire itself. |

- `(http-timeout-ms opts) → ms` returns the HTTP timeout each request carries: `:timeout-ms` + `:admission-ms` + `wire-margin-ms`. Pass it `opts` with both keys present, as `renderer` merges them. The sidecar is given the smaller budget, so it refuses a late render before the JVM stops waiting.
- `(validate-opts! opts) → opts` runs the option checks `renderer` runs and returns `opts` unchanged, throwing the option errors listed under `renderer`; it launches nothing. It does not merge the defaults itself, so pass it `opts` merged over them, as `renderer` does (`:node` `"node"` with `:module`, `:endpoint` without).
