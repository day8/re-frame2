# Realm symbol still fires on the tool-spec surface (positive fixture)

The tool-spec install!-convention exemption is NARROW: it covers only `install!`
/ `reinstall!`. A REALM-SPECIFIC retired symbol in a tool-spec code fence is
still drift and must FIRE (one finding) even when scanned as a tool-spec file.

```clojure
(rf/dispose-realm! the-realm)
```
