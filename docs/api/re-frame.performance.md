# re-frame.performance

Measure how long events, subscriptions, effects and view renders take in a production build. With timing on, re-frame2 records each one as a User Timing measure, so browser DevTools, an APM or any `PerformanceObserver` can read it. This is separate from the trace stream, which exists only in development builds: in development, Xray and the trace stream already time each run and also say why it ran, so reach for this when you need timings from the build you ship.

```clojure
(:require [re-frame.performance :as perf])
```

You never call anything in this namespace, so application code does not need that require. You turn timing on with two compile-time flags, named fully qualified in your build's `:closure-defines`:

```clojure
;; shadow-cljs.edn
{:builds {:app {:compiler-options
                {:closure-defines {re-frame.performance/enabled? true}}}}}
```

- Each measure is named `rf:<kind>:<id>`: `rf:event:<event-id>` for an event handler, `rf:sub:<query-id>` for a subscription recompute (a cached read records nothing), `rf:fx:<fx-id>` for an effect handler, or `rf:render:<view-id>` for a render of a view registered with `reg-view` or defined with Fresco's `defview`. A keyword id is written without its leading colon, as in `rf:event:todo/add`. No `performance.mark` entries are created.
- Measures are delivered to observers, then cleared by name straight after they are emitted, so `performance.getEntriesByType("measure")` returns none of them unless `retain-entries?` is on.
- Both flags are off by default. With the defaults, an `:advanced` build removes every timing call, so a shipped binary carries no User Timing code.
- CLJS only. On the JVM both flags are constant `false` and timing does nothing, because the Performance API exists only in the browser.

To look at the measures, record a profile in the browser DevTools **Performance** panel: each one appears as a named bar on the timeline (the Timings track in Chrome), beside React's renders, layout and paint. The panel captures entries as they are emitted, so it sees them without `retain-entries?`. For telemetry, attach a `PerformanceObserver` and forward the `rf:` measures to your APM:

```clojure
(.observe (js/PerformanceObserver.
            (fn [entries _]
              (doseq [e (.getEntries entries)
                      :when (.startsWith (.-name e) "rf:")]
                (js/console.log (.-name e) (.-duration e)))))   ;; or send to your APM
          #js {:type "measure"})
```

[Find and fix a slow view](../core/how-to/fix-a-slow-view.md) walks through turning timing on and reading the entries.

## Compile-time flags

### `enabled?`

- **Kind**: var (compile-time `goog-define` boolean)
- **Signature**: set with `:closure-defines {re-frame.performance/enabled? true}` (CLJS). On the JVM it is `^:const false`.
- **Description**: Turns on the timing measures for event handling, subscription recomputes, fx handlers and view renders. Default `false`.
    - Each measure is emitted as `performance.measure(name, {start, end})`, with numeric `performance.now()` timestamps.
    - The measure is emitted in a `try/finally`, so it is recorded even when the measured code throws; the exception still propagates.
    - Unless `retain-entries?` is on, the entry is cleared by name (`performance.clearMeasures`) straight after it is emitted. A live `PerformanceObserver` still receives it, because observer callbacks fire when `measure()` is called, before the clear.
    - It is read at compile time only: it is not a `rf/configure!` option, and changing it at runtime has no effect. With the default, every measure site is removed under `:advanced`.

### `retain-entries?`

- **Kind**: var (compile-time `goog-define` boolean)
- **Signature**: set with `:closure-defines {re-frame.performance/retain-entries? true}` (CLJS). On the JVM it is a constant `false`.
- **Description**: Keeps measure entries in the browser's User Timing buffer instead of clearing each one after it is emitted. Default `false`.
    - Turn it on for one-shot reads with `performance.getEntriesByType("measure")` in the console. A DevTools Performance recording does not need it.
    - Leave it off for long-running sessions such as real-user monitoring. The browser's measure buffer has no size limit, so retained entries accumulate for the life of the page. With it off, each entry is delivered to any live `PerformanceObserver` and cleared, so the buffer does not grow and `getEntriesByType` returns no `rf:*` entries.
    - It has no effect unless `enabled?` is also on.
    - Like `enabled?`, it is read at compile time only; changing it at runtime has no effect.
- **Example**:
  ```clojure
  ;; shadow-cljs.edn — retain entries for one-shot DevTools / console reads.
  ;; Leave off for long-running sessions; read via a PerformanceObserver.
  {:builds {:app {:compiler-options
                  {:closure-defines {re-frame.performance/enabled?        true
                                     re-frame.performance/retain-entries? true}}}}}
  ```

## See also

- [Configure dev and prod](../core/how-to/configure-dev-and-prod.md) — how the timing flag combines with `goog.DEBUG` across build profiles.
- [Observability](../core/observability.md) — how production timing sits alongside the trace and error surfaces.
