(ns re-frame.ssr.constants
  "Reserved string constants the SSR runtime, host adapters, and CLJS
  bootstrap all reference. Pinning these in one ns (rather than scattered
  literal strings) makes the convention discoverable and renameable —
  tooling that searches for the convention reads one var, not a regex
  pattern across the codebase.

  Per Spec 011 §Hydration payload script id (audit rf2-cegm7 CQ-3 /
  rf2-j54ee).")

(def ^:const payload-script-id
  "The literal `id` the host-adapter HTML shell stamps onto the
  hydration-payload `<script>` tag, AND the literal the client-side
  bootstrap reads via `document.getElementById(...)`.

  Spec 011 §Hydration payload script id pins this string as the
  contract between server-shell-emit and client-bootstrap-read. A user
  who overrides `:html-shell` MUST emit the payload `<script>` with
  this id (or substitute their own bootstrap that reads a custom id;
  the framework's bundled bootstrap reads only this id)."
  "__rf_payload")
