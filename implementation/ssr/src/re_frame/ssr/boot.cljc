(ns re-frame.ssr.boot
  "Client-side hydration boot helper — the symmetric counterpart of the
  server-side `re-frame.ssr.ring/ssr-handler`. Per Spec 011 §Client flow
  and §Client-side hydration boot helper (rf2-lq2ou).

  ## The symmetry

  The server side ships ONE explicit handler-constructor:

      (ssr-ring/ssr-handler {:on-create … :root-view … :payload …})
      ;; → a Ring handler that renders, builds the `__rf_payload`
      ;;   `<script>`, and writes the wire response.

  The client side now ships its mirror — ONE explicit boot call:

      (ssr/hydrate! {:frame :rf/default :render-tree-fn #(…hiccup…)})
      ;; → reads `__rf_payload`, dispatches `:rf/hydrate`, and (after
      ;;   the first render) verifies the render-tree hash against the
      ;;   server's.

  The pair reads as one contract: the server emits the payload under the
  `re-frame.ssr.constants/payload-script-id` id; the boot helper reads
  that SAME id (`document.getElementById`), parses the EDN with the same
  reader the server's `pr-str` round-trips through, and seeds the frame
  via the framework's `:rf/hydrate` event (locked `:replace-app-db`
  policy, Spec 011 §The :rf/hydrate event).

  Before this helper every host re-implemented the same five-line read →
  dispatch → render → verify dance (three testbeds + two worked examples
  carried byte-identical `read-server-payload` fns). The helper makes the
  client boot a one-liner and pins the read/dispatch/verify ordering the
  spec mandates so a host can't get it subtly wrong (e.g. verifying
  before the first render, or reading a stale id).

  ## Platform split

  - `read-server-payload` is `:cljs`-only — it reaches into the DOM.
  - `hydrate!` is platform-neutral: it takes an explicit `:payload` (or
    reads it from the DOM on CLJS when omitted), dispatches `:rf/hydrate`,
    and runs the post-render `verify-hydration!` step. The JVM test
    harness drives `hydrate!` with an explicit payload + a `:client`-
    platform frame so the round-trip (server `build-payload` →
    `hydrate!` → post-hydrate sub) is exercised without a browser.

  Per the rf2-uo7v shipping convention this namespace lives in the
  `day8/re-frame2-ssr` artefact alongside the rest of the SSR surface; it
  is re-exported from the `re-frame.ssr` façade as `ssr/hydrate!` /
  `ssr/read-server-payload`."
  (:require [re-frame.router :as router]
            [re-frame.ssr.hydrate :as hydrate]
            ;; `constants` + `cljs.reader` are only used by the CLJS-only
            ;; `read-server-payload` (DOM read); require them on CLJS so a
            ;; JVM lint of this `.cljc` doesn't flag them unused.
            #?(:cljs [re-frame.ssr.constants :as constants])
            #?(:cljs [cljs.reader :as reader])))

#?(:clj (set! *warn-on-reflection* true))

#?(:cljs
   (defn read-server-payload
     "Read the EDN hydration payload baked into the page by the server's
     HTML shell. Reads the `<script>` element whose id is
     `re-frame.ssr.constants/payload-script-id` (`\"__rf_payload\"`) — the
     SAME id the host adapter's `default-html-shell` /
     `payload-script-tag` stamps (Spec 011 §Hydration payload script id).

     Returns the parsed payload map, or `nil` when the page was not
     server-rendered (no payload script present) — the \"client-only
     first load\" shape. The server escapes `<` to the EDN `\\u003c`
     unicode escape before injection (the rf2-7ksyr `</script>` XSS gate);
     `cljs.reader/read-string` accepts that escape so the payload
     round-trips unchanged.

     A host that overrode `:html-shell` with a custom payload id must
     read that id itself rather than calling this fn (the framework's
     bundled boot reads only the pinned id)."
     ([] (read-server-payload constants/payload-script-id))
     ([element-id]
      (when-let [el (.getElementById js/document element-id)]
        (reader/read-string (.-textContent el))))))

(defn hydrate!
  "Boot the client from the server's hydration payload — the symmetric
  client-side counterpart of `re-frame.ssr.ring/ssr-handler`. Per Spec
  011 §Client flow.

  Three steps, in the order the spec mandates:

    1. READ    — the payload. Supplied explicitly via `:payload`, or (on
                 CLJS) read from the DOM's `__rf_payload` `<script>` via
                 `read-server-payload` when `:payload` is omitted.
    2. HYDRATE — `dispatch-sync` `[:rf/hydrate payload]` against the
                 target frame BEFORE the first render, so the frame's
                 app-db is the server's authoritative slice when the view
                 first evaluates (locked `:replace-app-db` policy, Spec
                 011 §The :rf/hydrate event). Skipped when there is no
                 payload (client-only first load) — the caller renders
                 against the empty app-db.
    3. VERIFY  — after the first render, `verify-hydration!` compares the
                 client render-tree hash against the server hash the
                 `:rf/hydrate` handler stashed at
                 `[:rf/runtime :ssr :hydration :server-hash]`. A mismatch
                 emits `:rf.ssr/hydration-mismatch` (Spec 011
                 §Hydration-mismatch detection). The render itself is the
                 HOST's job (Reagent/UIx/Helix `render`) — the helper
                 cannot mount the DOM for you — so the verify step takes a
                 `:render-tree-fn` the host supplies: a 0-arity fn the
                 helper calls AFTER you have rendered, returning the same
                 render-tree the host just mounted (typically
                 `#((rf/view :app/root))`). When `:render-tree-fn` is
                 omitted the verify step is skipped (the host opts out of
                 hash-mismatch detection, or runs `verify-hydration!`
                 itself at its own render site).

  Opts:

    :frame          — the target frame id. Default `:rf/default`. Must be
                      a `:client`-platform frame for the compatibility-
                      check fxs to fire (Spec 011 §The :rf/hydrate event;
                      a `:server`-platform frame skips them per rf2-7bcn0).
    :payload        — the hydration payload map (the `:rf/hydration-payload`
                      shape). When omitted, read from the DOM on CLJS;
                      on the JVM it is required (no DOM to read from).
    :render-tree-fn — (optional) a 0-arity fn returning the client
                      render-tree to hash for mismatch detection. Called
                      ONCE, after `:rf/hydrate`, on the verify step. Omit
                      to skip verification.
    :element-id     — (CLJS, optional) override the payload `<script>` id
                      to read when `:payload` is omitted. Default the
                      pinned `__rf_payload`.

  Returns the payload that was applied (or `nil` on a client-only first
  load) so the caller can branch on \"was this server-rendered?\" without
  re-reading the DOM.

  Example (Reagent client boot):

      #?(:cljs
         (defn ^:export run []
           (rf/init! reagent-adapter/adapter)
           (let [payload (ssr/hydrate! {:render-tree-fn #((rf/view :app/root))})]
             (rdc/render react-root [(rf/view :app/root)])
             ;; …verify already ran inside hydrate! against render-tree-fn
             payload)))

  Hosts that need to interleave their own render between hydrate and
  verify (e.g. async mount) can call `dispatch-sync [:rf/hydrate …]`
  directly and then `verify-hydration!` at their render site — `hydrate!`
  is the convenience that fuses the common ordering."
  [{:keys [frame payload render-tree-fn element-id]
    :or   {frame :rf/default}}]
  ;; `element-id` is consumed only by the CLJS DOM read; discard-bind it so
  ;; a JVM lint of this `.cljc` doesn't flag it as an unused
  ;; `:clj`-expansion binding (the read itself stays CLJS-only).
  (let [_       element-id
        payload (or payload
                    #?(:cljs (read-server-payload
                               (or element-id constants/payload-script-id))
                       :clj nil))]
    (when payload
      ;; HOT PATH — seed app-db from the server's slice BEFORE first render.
      ;; `router/dispatch-sync!` is the fn-form `re-frame.core` re-exports
      ;; as `dispatch-sync*` (no call-site source-coord capture — this is
      ;; programmatic boot, not a hand-written call site). Requiring the
      ;; router directly keeps `boot` on the granular-require convention
      ;; the other ssr sub-namespaces follow (frame/events/trace, never
      ;; the `re-frame.core` public façade).
      (router/dispatch-sync! [:rf/hydrate payload] {:frame frame})
      ;; HOT PATH — post-render hash-mismatch detection. Symmetric with
      ;; the server's `:emit-hash?`-stamped `data-rf-render-hash` marker.
      (when render-tree-fn
        (hydrate/verify-hydration! frame (render-tree-fn))))
    payload))
