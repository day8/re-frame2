# Effects and React interop

## Use an effect only to synchronize with an external system

Examples:

- DOM measurement/listener not covered by React props;
- WebSocket or browser API listener;
- imperative chart/editor/map lifecycle;
- document title or media API;
- third-party store subscription;
- timer whose lifecycle genuinely follows the component.

Do not use an effect to derive data, copy props into state, run ordinary re-frame2 workflow, or fetch a resource that should be owned by a route/event/lease.

## Passive effect

```clojure
(react/use-effect [topic]
  (fn []
    (let [unsubscribe (subscribe-to-topic! topic on-message)]
      unsubscribe)))
```

The cleanup function runs before a changed effect reattaches and on unmount. React Strict Mode may run setup/cleanup/setup during development. Setup and cleanup must be symmetric and idempotent.

Returning `nil` means no cleanup, but any listener, timer, or host object normally needs one.

## Layout effect

```clojure
(let [node (react/use-ref nil)]
  (react/use-layout-effect [placement]
    (fn []
      (position-overlay! (.-current node) placement)
      nil))
  [:div {:ref node}])
```

Use layout effect only when work must happen after DOM mutation and before paint. It blocks paint; ordinary connections/listeners belong in passive effect.

## Frame-correct callbacks

```clojure
(let [dispatch! (ui/dispatch-fn)]
  (react/use-effect [url]
    (fn []
      (let [socket (open-socket! url)]
        (.addEventListener socket "message"
          (fn [e]
            (dispatch! [::message (.-data e)])))
        #(.close socket)))))
```

For a callback created inside an effect, use a normal local function when its lifetime is exactly that effect, or `react/use-effect-event` when it must read changing committed values without reconnecting. `ui/handler` is a compiler template callback form and is not valid inside an effect body.

The important rule is that `dispatch!` was obtained during render and reads the committed frame. A bare ambient dispatch inside a later browser callback has no dynamic frame binding.

`ui/dispatch-fn` is intentionally inactive once layout disconnection starts. A passive-effect cleanup that must emit a domain event after that point uses an explicitly captured `(rf/capture-frame target)` operation instead; do not keep an incidental view owner alive merely so unmount can dispatch.

## Latest committed values without reconnecting

```clojure
(let [dispatch! (ui/dispatch-fn)
      on-message
      (react/use-effect-event
        (fn [message]
          (when notifications-enabled?
            (dispatch! [::notification message]))))]
  (react/use-effect [room-id]
    (fn []
      (connect-room! room-id on-message))))
```

Changing `notifications-enabled?` does not reconnect the room, but the callback sees the latest committed value. Include every value that should recreate the connection in the effect deps; effect events are not a way to hide dependencies.

## Imperative widget bridge

```clojure
(ui/defview code-editor-bridge [{:keys [document on-change-event]}]
  (let [host (react/use-ref nil)
        dispatch! (ui/dispatch-fn)]
    (react/use-effect [document on-change-event]
      (fn []
        (let [editor (editor/create! (.-current host)
                       {:document document
                        :on-change
                        (fn [value]
                          (dispatch! (conj on-change-event value)))})]
          #(editor/destroy! editor))))
    [:div.editor-host {:ref host}]))
```

For large document updates, split mount and update effects so the host object is not recreated. The bridge owns the imperative object; the outer application view owns re-frame2 reads.

## Foreign component

```clojure
[DatePicker
 {:selected selected-date
  :min-date minimum
  :on-change
  (ui/handler [date]
    (dispatch! [::date-selected date]))}]
```

Foreign props become a JS object. Nested JS values remain JS. Clojure persistent values remain Clojure values; convert only where the API requires JS arrays/objects.

The compiler does not know most foreign prop schemas. Mistyped prop names are the integrating code's responsibility unless a generated binding supplies metadata.

## Render props

```clojure
[Measure
 {:children
  (ui/render-fn [{:keys [bounds ref]}]
    [:div {:ref ref}
     (str (:width bounds) " × " (:height bounds))])}]
```

`ui/render-fn` is called during the foreign component's render, sees current render values, and must stay pure. It cannot use Hooks, `ui/sub`, or `ui/lease`. Return a named child `defview` when the subtree is reused, reactive, or important enough to inspect independently in Xray.

The shown map destructure reads literal properties from the JS object passed by `Measure`; it does not convert an arbitrary JS object into a Clojure map.

## Callback choices

| Need | Form |
|---|---|
| Dispatch event from DOM | Direct event vector or `ui/event`. |
| Stable post-commit component/DOM callback | `ui/handler`. |
| Pure callback invoked during foreign render | `ui/render-fn`. |
| Stable frame dispatch in an effect | `ui/dispatch-fn`. |
| Latest values inside a persistent effect | `react/use-effect-event`. |
| Foreign API observes callback identity changes | `ui/raw-handler`, with an explanation. |

Do not use `useCallback` reflexively. Use it only for a foreign Hook/API contract that specifically takes a dependency-stable function and is not covered by the compiler forms.

## Refs

```clojure
(let [input-ref (react/use-ref nil)]
  [:<>
   [:input {:ref input-ref}]
   [:button
    {:on-click
     (ui/handler [_]
       (.focus (.-current input-ref)))}
    "Focus"]])
```

Read refs in effects or callbacks, not during render. `ref.current` is mutable host state and does not trigger rendering.

Internal views must explicitly accept/forward `:ref` when they form a ref boundary. Avoid `findDOMNode`; React 19 does not support it.

## Portals

```clojure
(when open?
  (ui/portal modal-root
    [modal {:close-event [::closed]}]))
```

Portals preserve React/re-frame2 context and logical parent ownership. Ensure the target exists before render and consider SSR: a portal normally needs a server placeholder or client-only boundary.

## Error boundary

```clojure
(ui/defview widget-error [{:keys [error retry-event]}]
  [:section.error
   [:h2 "Widget failed"]
   [:pre (str error)]
   [:button {:on-click (ui/event retry-event)} "Try again"]])

[ui/error-boundary
 {:fallback widget-error
  :fallback-props {:retry-event [::widget-retried widget-id]}
  :reset-key [widget-id attempt]}
 [ForeignWidget {:id widget-id}]]
```

The boundary catches render/lifecycle errors below it and emits structured re-frame2 error evidence. It does not catch event-handler or arbitrary async callback errors; those belong to their event/resource/effect boundary.

The boundary injects `:error` and merges the declared fallback props. The retry event updates application state, including `attempt`; the resulting `:reset-key` change resets the caught state. Recovery remains an explicit application event, not a generic “swallow” policy.

## React context

Use `react/use-context` for genuine React-owned integration context such as a third-party theme or router. re-frame2 application state should remain subscriptions, and frame context is already integrated automatically.

```clojure
(let [theme (react/use-context ThemeContext)]
  ...)
```

The compiler records a context cause when it can identify the context site. A raw foreign context update may appear as `:foreign-or-react` in Xray.

## Hidden Activity

React 19.2 `<Activity mode="hidden">` preserves local component/DOM state but disconnects effects and subscriptions. re-frame2 UI follows that lifecycle: hidden ViewCells release subscription and resource owners and stop receiving framework invalidations; reveal reconnects and checks current values before paint.

Use Activity only in browser-only UI or behind `ui/client-only`. The JVM SSR emitter does not imitate React DOM's private Activity/selective-hydration markers.

Activity does not turn re-frame2 reads into prefetch. Cause likely-next resource work through a route, event, or machine. Also clean up DOM-owned side effects such as playing media: hidden DOM remains present even though React effects disconnect.

## Dynamic and existing elements

```clojure
(ui/element component-type {:value value})
(ui/raw element-from-library)
```

These are explicit React boundaries, not Hiccup interpretation. Use `ui/client-only` when JVM cannot render the element:

```clojure
(ui/client-only
  {:fallback [:div.map-placeholder "Map loads in the browser"]}
  [MapView {:center center}])
```

## Lazy code

Use React lazy/Suspense for JavaScript code loading, not application data loading. The fallback should have deterministic SSR/hydration semantics. Data/resource status remains explicit re-frame2 state.

## Cleanup checklist

For every effect/bridge, verify:

- setup has one matching cleanup;
- cleanup uses the exact captured host object/frame/target;
- setup-cleanup-setup works under Strict Mode;
- a changed dependency does not leak old listeners;
- unmount during in-flight async work aborts or ignores stale replies through the owning subsystem;
- SSR does not rely on the effect;
- HMR remount/preserve behavior is acceptable;
- Xray can see the re-frame2 event/resource consequences.

If several application components repeat the same host lifecycle, extract a small integration view or library. Do not add it to the substrate until it represents a framework-owned concept.
