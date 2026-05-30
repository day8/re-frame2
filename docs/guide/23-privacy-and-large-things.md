# 23 - Privacy and large data

You want great tools without accidentally shipping auth tokens, patient records, or forty-megabyte blobs through a share URL because a debug panel got enthusiastic. This chapter teaches re-frame2's privacy and large-data posture: classify data, elide at egress, and assume off-box consumers are hostile until proven otherwise.

The trace surface is useful because it sees real evidence. That is exactly why it needs rules.

## Mark sensitive data

Attach sensitivity where the data shape is declared.

```clojure
(rf/reg-app-schema [:session :token]
  [:string {:sensitive? true}])
```

A sensitive value should appear as `:rf/redacted` when it leaves the trusted runtime surface.

## Mark large data

Large values are not secrets, but they can ruin tools and URLs.

```clojure
(rf/reg-app-schema [:upload :bytes]
  [:bytes {:large? true}])
```

The egress path can replace the value with a summary marker instead of hauling the entire thing into every trace, Story snapshot, or exported artifact.

## Egress is the boundary

The important question is not "did the runtime ever hold the value?" Of course it did; it is your app. The question is "what leaves the runtime?" Share links, static exports, screenshots, copied JSON, MCP responses, telemetry shippers, and CI artifacts all need the same conservative instinct.

## Pitfall: redacting only logs

Logs are not the only leak path. A value can leak through a trace event, Story URL, exported fixture, failed test artifact, browser screenshot, or exception message. Treat egress as a family of surfaces, not one `console.log` call.
