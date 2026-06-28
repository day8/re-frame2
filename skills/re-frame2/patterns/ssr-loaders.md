# Pattern — SSR Loaders (parallel data fetch during drain)

The standard fan-out-then-render shape for server-side rendering that needs N parallel HTTP fetches before HTML emission. **Convention, not Spec** — built on `:spawn-all` (spawn-and-join) and `:rf.http/managed`, primitives the spec already locks.

> **Mental-model anchor:** this is the **Next.js `Promise.all([getArticle, getComments, getRelated])` / Remix parallel-loader** shape. The naive alternative — three loader events in series from `:initial-events` — serialises the wall-clock cost of every fetch (the drain is single-threaded; back-to-back blocking JVM transport calls add up). The fan-out moves each fetch into its own spawned actor; total cost falls to `max(fetch-i) + overhead`, not `sum(fetch-i)`.

## When to load

The prompt mentions: SSR that loads several independent pieces of data before render, "fetch product + related + reviews in parallel", a server-side `Promise.all`, a per-page loader, or a navigation-fetch that fans out N requests. The same machine drives client navigation-fetch too — only the spawn site and the rendering moment change.

## The five-step shape

A boot-like state machine spawned from `:initial-events` (server) / `:on-match` (client). Its first state fans out N HTTP children via `:spawn-all`; the join-all-complete transition advances to a terminal `:ready`; the drain settles; `render-to-string` runs against the post-drain `app-db`.

1. **`:rf/server-init` (the frame's `:initial-events` step) reads the URL** and spawns the loader machine with the request-derived params.
2. **The loader's `:loading` state declares `:spawn-all`** — N children, each a thin machine wrapping `:rf.http/managed` for one fetch.
3. **Each child dispatches back** `[<parent> [:loaded :child-id <result>]]` (or `[:failed …]`) on terminal.
4. **The runtime joins** — when every child is `:done`, it fires `:on-all-complete`; the parent transitions to `:ready` and writes results into `app-db`.
5. **The drain settles, `render-to-string` runs** — views `subscribe` to the slices; the hydration payload carries the same slices to the client.

## Canonical declaration

The loader machine fans out three fetches, joins, writes on `:ready`, and stamps a 502 on failure or deadline:

```clojure
(rf/reg-machine :pdp/load
  {:doc     "Parallel loader for /products/:id. Fans out 3 fetches; joins on all-complete."
   :initial :loading
   ;; Top-level :data is a literal map (Spec 005 §build-initial-snapshot
   ;; seeds it verbatim — NO fn-form here; the fn-form lives only on a
   ;; spawn-spec :data). :product-id arrives as the spawned actor's
   ;; initial data — the :rf/server-init wiring below spawns with
   ;; :data {:product-id …}.
   :data    {:product-id nil :results {}}
   :actions
   {;; The child dispatches [:pdp/child-loaded :slot {...}]; this runs as
    ;; an INTERNAL self-transition (no :target) and stages each child's
    ;; result under :data. The :on-child-done keyword (:pdp/child-done) is
    ;; intercepted by the join machinery for the join-count; this SEPARATE
    ;; non-intercepted keyword (:pdp/child-loaded) carries the payload.
    :stage-result
    (fn [{data :data [_ slot payload] :event}]
      {:data (assoc-in data [:results slot] payload)})
    ;; On :ready, hand the staged results to a real reg-event that does
    ;; the app-db write — a machine :action cannot write :db itself.
    :apply-results
    (fn [{data :data}]
      {:fx [[:dispatch [:pdp/apply-results (:results data)]]]})}
   :states
   {:loading
    {:spawn-all
     {:children
      [{:id :product :machine-id :http/get-one
        :data (fn [{:keys [snapshot]}] {:url (str "/api/products/" (-> snapshot :data :product-id))
                                        :decode ProductSchema})}
       {:id :related :machine-id :http/get-one
        :data (fn [{:keys [snapshot]}] {:url (str "/api/products/" (-> snapshot :data :product-id) "/related")
                                        :decode RelatedListSchema})}
       {:id :reviews :machine-id :http/get-one
        :data (fn [{:keys [snapshot]}] {:url (str "/api/products/" (-> snapshot :data :product-id) "/reviews")
                                        :decode ReviewListSchema})}]
      :join            :all
      :on-child-done   :pdp/child-done    ;; child-keyword children dispatch on success (REQUIRED)
      :on-child-error  :pdp/child-error    ;; child-keyword children dispatch on failure (REQUIRED)
      :on-all-complete [:pdp/joined]
      :on-any-failed   [:pdp/load-failed]}
     :after {30000 :pdp/timed-out}        ;; phase-level wall-clock guard — mandatory under SSR
     :on    {:pdp/child-loaded {:action :stage-result}   ;; internal: stage each payload under :data
             :pdp/joined  {:target :ready :action :apply-results}
             :pdp/load-failed :error
             :pdp/timed-out   :error}}

    :ready {:final? true}
    :error {:final? true
            :entry (fn [{:keys [event]}]
                     (let [[_ _ reason] event]
                       {:fx [[:rf.server/set-status 502]
                             [:dispatch [:pdp/stamp-error reason]]]}))}}})

;; The real app-db writes happen in ordinary reg-event handlers — a
;; machine :action / :entry returns only :data + :fx, never :db (Spec 005
;; hard-disallows :db; there is no :db-fx key and no :assoc-in fx).
(rf/reg-event :pdp/apply-results
  (fn [{:keys [db]} [_ {:keys [product related reviews]}]]
    {:db (-> db
             (assoc-in [:pdp :product] product)
             (assoc-in [:pdp :related] related)
             (assoc-in [:pdp :reviews] reviews))}))

(rf/reg-event :pdp/stamp-error
  (fn [{:keys [db]} [_ reason]]
    {:db (assoc-in db [:pdp :error] reason)}))
```

The per-fetch child is a thin shared machine — one state spawns `:rf.http/managed`; terminal states dispatch the success keyword (`[:pdp/load [:pdp/child-loaded :product {…}]]` carrying the payload, plus the join keyword `[:pdp/load [:pdp/child-done :product]]`) or `:pdp/child-error` back to the parent (the parent-id is stamped at spawn time and read from the child's `:data :env`). Only the spawn-spec `:data` fn differs per sibling.

> **Why two keywords per child.** `:on-child-done` / `:on-child-error` are REQUIRED keyword slots on `:spawn-all` (omitting either throws `:rf.error/machine-spawn-all-bad-shape`). The runtime *intercepts* events whose inner-event-id matches those keywords to drive the join count — so the join keyword carries no usable payload to the parent's `:on` table. To thread each child's result in, the child dispatches a SECOND, non-intercepted keyword (`:pdp/child-loaded`) the parent stages via an internal self-transition, as the boot example (`examples/patterns/boot/boot.cljs`) does.

The SSR request wires it from `:rf/server-init`, reading request-derived values from the `:rf.server/request` cofx **once**, at the spawn site:

```clojure
(rf/reg-event :rf/server-init
  {:platforms #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [rf.server/request]} _]
    (let [{:keys [product-id]} (match-route (:url request))    ;; app-supplied route matcher
          auth-token (-> request :session :token)]
      {:fx [[:rf.machine/spawn {:machine-id :pdp/load
                                :data       {:product-id product-id :auth-token auth-token}}]]})))
```

Children read auth-token/locale from the parent's snapshot at spawn — nothing in the child reaches for the request cofx again, which keeps the child **reusable on the client** (where there is no request cofx).

## The deadline

`:after {30000 :pdp/timed-out}` is the SSR-specific knob. A client tolerates "spinner for 60 s then resolves"; a server has a single render moment and a request-timeout budget. If one fetch hangs past the deadline, `:after` exits `:loading`, the exit cascade tears down every surviving child (their `:rf.http/managed` invocations abort via the destroy fx), and `:error` stamps a 502 — no partial render. Pick a deadline **shorter** than the host adapter's request timeout.

## Server vs client

The same machine works on both platforms; only the deadline policy and rendering moment differ. **Server**: spawned from `:initial-events`, drain settles before `render-to-string`, deadline mandatory, no partial render. **Client**: spawned at the route's `:on-match`, drain settles before the next React tick, deadline optional, *can* render a skeleton mid-fetch (give the parent an `:on :loaded` handler writing results incrementally).

## Anti-patterns

- **Three loaders in series from `:initial-events`.** Serialises the wall-clock cost — each `:rf.http/managed` blocks the drain thread on the server JVM transport. Use `:spawn-all`.
- **Hand-rolling the join** with a counter in `app-db` (`(when (= 3 @counter) …)`). Reinvents `:spawn-all`'s join-state without the destroy cascade, deadline composition, or trace events.
- **Reading `:rf.server/request` from child machines.** The cofx is server-only; a child that reads it becomes server-only too, breaking the "same machine for client navigation" property. Thread request-derived values from the parent's `:data`.
- **Omitting the deadline.** A loader with no `:after` can hang until the host's outer timeout fires, where the error path is host-specific and unobservable to the trace stream.
- **Writing results into `app-db` from the child.** The child dispatches back; the parent's `:ready :entry` writes. Keeps the join atomic — partial results never land on failure/deadline short-circuit.

## Worked example

`examples/patterns/boot/boot.cljs` exercises the closest live shape — its `:loading-deps` state fans out three parallel loaders via `:spawn-all`. The `/products/:id` shape above is the per-page SSR analogue; substitute the route + per-fetch schemas.

## Pointers

- Spec: [`spec/Pattern-SSR-Loaders.md`](../../../spec/Pattern-SSR-Loaders.md) — full worked `/products/:id` page, the per-fetch child machine, retry partitioning (transport vs semantic), abort cascade, the server-vs-client semantics table, conformance checklist.
- Substrate: `SKILL-REDIRECT.md` → *EP — State machines (005)* (`:spawn-all` join, phase-level `:after`, spawn-id tracking, retry-ownership boundary), *EP — SSR (011)* (server flow, `:rf.server/request` cofx), *EP — HTTP requests (014)* (the managed-HTTP fx each child wraps, aborts).
- Compose: `patterns/boot.md` (the app-wide boot sibling; this is the per-page analogue), `patterns/form-action.md` (the POST-path sibling — a page uses Loaders for GET, FormAction for POST), `patterns/remote-data.md` (the per-slice lifecycle each child's result lands in).

---

*Derived from `spec/Pattern-SSR-Loaders.md` (Convention, not Spec) @ main, with the `:spawn-all` fan-out cross-checked against `examples/patterns/boot/boot.cljs`. Re-verify if `:spawn-all` join semantics change.*
