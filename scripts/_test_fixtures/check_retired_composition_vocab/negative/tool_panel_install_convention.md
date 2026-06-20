# Tool panel install! convention (negative fixture, tool-spec surface)

The Xray / Story tools name each panel's registration entry point `install!`.
On the TOOL-SPEC surface a bare `install!` / `reinstall!` is that convention, not
the retired `rf/install!` facade constructor — it must stay GREEN (scanned as if
under `tools/<tool>/spec/`).

```clojure
(defn install!
  "Idempotent install for the <Panel>'s Xray-side registrations."
  []
  (subs/install!)
  (events/install!)
  nil)
```
