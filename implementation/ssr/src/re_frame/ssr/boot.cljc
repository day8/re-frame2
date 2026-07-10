(ns re-frame.ssr.boot
  "Client-side hydration boot helpers.

  `hydrate!` preserves the required read → hydrate → verify ordering. It
  dispatches synchronously before the host mounts, then optionally hashes a
  caller-supplied pure render-tree computation. It does not inspect or own the
  DOM mount. Hosts that must verify a substrate-mutated or asynchronous tree
  perform the hydrate and verify steps separately.

  `read-server-payload` is CLJS-only and reads the pinned `__rf_payload`
  element. `hydrate!` is platform-neutral when given an explicit payload."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.router :as router]
            [re-frame.ssr.hydrate :as hydrate]
            [re-frame.trace :as trace]
            ;; `constants` + `cljs.reader` are only used by the CLJS-only
            ;; `read-server-payload` (DOM read); require them on CLJS so a
            ;; JVM lint of this `.cljc` doesn't flag them unused.
            #?(:cljs [re-frame.ssr.constants :as constants])
            #?(:cljs [cljs.reader :as reader])))

#?(:clj (set! *warn-on-reflection* true))

(defn dispatch-malformed-hydration-frameless!
  "Surface a pre-frame hydration parse failure as a frameless, always-on
  `:rf.error/malformed-hydration-payload` record. The
  `read-server-payload` DOM read runs BEFORE `hydrate!` resolves the target
  frame, so there is no `:frame` to carry (`:frame nil`) — exactly the
  record carries `:frame nil`. Corrupt hydration input is a fail-closed
  boundary event, so the record reaches the always-on error emitter even when
  development tracing is disabled. The helper is platform-neutral so JVM
  tests can exercise the record shape without a DOM. Returns nil when the
  error-emitter hook is absent."
  [where element-id reason]
  (when-let [dispatch-error-record!
             (late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:error      :rf.error/malformed-hydration-payload
       :frame      nil
       :time       (interop/now-ms)
       :where      where
       :failing-id :rf/hydrate
       :element-id element-id
       :reason     reason
       :recovery   :no-recovery}))
  nil)

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
     unicode escape inside string literals before injection (the
     rf2-7ksyr `</script>` XSS gate, EDN-aware per rf2-rdxxa);
     `cljs.reader/read-string` accepts that escape so the payload
     round-trips unchanged, including payloads carrying keyword/symbol
     tokens with `<`.

     A malformed payload script fails closed rather than crashing client boot:
     `cljs.reader/read-string` THROWS on malformed EDN, and an unguarded
     throw here propagates out of `hydrate!` and aborts the mount. We
     isolate the parse, emit a `:rf.error/malformed-hydration-payload`
     diagnostic, and return `nil` — the SAME \"no trustworthy server
     slice\" shape as a server-render-absent page, so the host falls back
     to a client-only first render (degraded-but-running, the Spec 011
     §The :rf/hydrate event posture). `cljs.reader/read-string` is the
     SAFE EDN reader (no `#=`/eval, unlike `clojure.core/read-string`),
     so the parse itself cannot execute injected code; this guard closes
     the remaining denial-of-service / crash-the-boot edge.

     A host that overrode `:html-shell` with a custom payload id must
     read that id itself rather than calling this fn (the framework's
     bundled boot reads only the pinned id)."
     ([] (read-server-payload constants/payload-script-id))
     ([element-id]
      (when-let [el (.getElementById js/document element-id)]
        (try
          (reader/read-string (.-textContent el))
          (catch :default e
            (let [reason (str "the __rf_payload hydration script "
                              "did not parse as EDN; treating the "
                              "page as client-only (no hydration). "
                              (ex-message e))]
              (when interop/debug-enabled?
                (trace/emit-error! :rf.error/malformed-hydration-payload
                                   {:where      'rf.ssr/read-server-payload
                                    :failing-id :rf/hydrate
                                    :element-id element-id
                                    :reason     reason
                                    :recovery   :no-recovery}))
              ;; Parsing precedes frame resolution, so the always-on record is
              ;; intentionally frameless.
              (dispatch-malformed-hydration-frameless!
                'rf.ssr/read-server-payload element-id reason))
            nil))))))

(defn- validate-payload-frame-id!
  "Assert that the explicit client hydration `target` agrees with the
  payload's `:rf/frame-id` — the SSR-wire spelling
  of the frame stamp the SERVER rendered under (built by
  `re-frame.ssr.payload-policy/build-payload`). Per Spec 011 §The
  hydration payload + Spec 002 §Frame target resolution: the payload
  frame-id is payload metadata + validation evidence, NOT a no-opts
  target resolver. A host that wants the payload's frame to be the client
  target passes that frame explicitly to `hydrate!`; if the explicit
  target conflicts with the payload's frame-id, hydration surfaces a
  structured `:rf.error/hydration-frame-id-mismatch` rather than silently
  choosing either side.

  A payload that carries NO `:rf/frame-id` (a host that omitted it, or a
  client-only/no-server-slice page) is NOT a conflict — there is nothing
  to disagree with, so the explicit target stands. Only a PRESENT-and-
  DIFFERENT frame-id is a mismatch."
  [target payload]
  (let [payload-frame-id (:rf/frame-id payload)]
    (when (and (some? payload-frame-id)
               (not= payload-frame-id target))
      (let [ex   (error/thrown-ex-info
                   :rf.error/hydration-frame-id-mismatch
                   'rf.ssr/hydrate!
                   (str "Hydration frame-id mismatch: the explicit "
                        ":frame target '" target "' conflicts with the "
                        "payload's :rf/frame-id '" payload-frame-id
                        "' (the frame the server rendered under). "
                        "Pass the same frame to hydrate! that the server "
                        "stamped, or correct the server's render frame — "
                        "the runtime will not silently choose a side.")
                   {:recovery :supply-matching-frame
                    :extra    {:failing-id       :rf/hydrate
                               :target-frame     target
                               :payload-frame-id payload-frame-id}})
            data (ex-data ex)]
        (when interop/debug-enabled?
          (trace/emit-error! :rf.error/hydration-frame-id-mismatch data))
        (throw ex)))))

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
                 first evaluates (locked `:replace-frame-state` policy, Spec
                 011 §The :rf/hydrate event). Skipped when there is no
                 payload (client-only first load) — the caller renders
                 against the empty app-db.
    3. VERIFY  — `verify-hydration!` compares the client render-tree hash
                 against the server hash the `:rf/hydrate` handler stashed
                 at `[:rf.runtime/ssr :hydration :server-hash]`. A mismatch
                 emits `:rf.ssr/hydration-mismatch` (Spec 011
                 §Hydration-mismatch detection). The verify step takes a
                 `:render-tree-fn` the host supplies: a 0-arity fn
                 returning the CLIENT render-tree to hash (typically
                 `#((rf/view :app/root))`). `hydrate!` calls it
                 SYNCHRONOUSLY, immediately after `:rf/hydrate` and BEFORE
                 the host's own render — see the ns docstring §The verify
                 contract for why hashing the pure client tree pre-mount is
                 equivalent to hashing the mounted tree (a re-frame2 view is
                 a pure fn of the just-hydrated app-db). The helper does not
                 — and cannot — observe the host's DOM mount;
                 `:render-tree-fn` is a PURE client-tree computation, not a
                 \"read back what was mounted\" hook. When `:render-tree-fn`
                 is omitted the verify step is skipped (the host opts out of
                 hash-mismatch detection, or runs `verify-hydration!`
                 itself at its own render site).

  Opts:

    :frame          — REQUIRED. The target frame id to hydrate into. The
                      same frame the host passes to the root provider,
                      streaming `install!`, resource preload, and Xray. The
                      client target is supplied, not synthesised. An absent
                      `:frame` emits + throws `:rf.error/no-frame-context`
                      rather than defaulting implicitly. Must
                      be a `:client`-platform frame for the compatibility-
                      check fxs to fire; a `:server`-platform frame skips them.
                      The payload's `:rf/frame-id` is VALIDATED against
                      this explicit target: a conflict surfaces a
                      structured `:rf.error/hydration-frame-id-mismatch`
                      rather
                      than silently choosing either side. The payload
                      frame-id is payload metadata + validation evidence,
                      NOT a no-opts target resolver.
    :payload        — the hydration payload map (the `:rf/hydration-payload`
                      shape). When omitted, read from the DOM on CLJS;
                      on the JVM it is required (no DOM to read from).
    :render-tree-fn — (optional) a 0-arity fn returning the client
                      render-tree to hash for mismatch detection. Called
                      ONCE, SYNCHRONOUSLY, immediately after `:rf/hydrate`
                      and before the host's own render (a pure client-tree
                      computation — see step 3). Omit to skip verification.
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
           ;; hydrate! seeds app-db AND verifies synchronously (computing
           ;; the client tree via render-tree-fn) BEFORE the host mounts.
           ;; The hydration target is supplied explicitly via `:frame`.
           (let [payload (ssr/hydrate! {:frame          :app/main
                                        :render-tree-fn #((rf/view :app/root))})]
             (rdc/render react-root
               [rf/frame-provider {:frame :app/main}
                [(rf/view :app/root)]])
             ;; …verify already ran inside hydrate! against render-tree-fn
             payload)))

  Hosts that must verify the actual MOUNTED DOM tree — a substrate that
  mutates the tree at mount time, or an async mount where the render-tree
  is not yet computable when `hydrate!` returns — split the convenience:
  call `dispatch-sync [:rf/hydrate …]` directly to seed, mount, then call
  `verify-hydration!` at the post-mount site with the observed tree.
  `hydrate!` is the convenience that fuses the common (pure-projection)
  ordering for the host whose render-tree is a pure function of app-db
  (the re-frame2 norm — Reagent / UIx / Helix all qualify)."
  ;; A nil frame is an absent target, never an implicit `:rf/default`.
  [{:keys [frame payload render-tree-fn element-id]}]
  ;; `element-id` is consumed only by the CLJS DOM read; discard-bind it so
  ;; a JVM lint of this `.cljc` doesn't flag it as an unused
  ;; `:clj`-expansion binding (the read itself stays CLJS-only).
  (let [_       element-id
        ;; The client hydration target is supplied explicitly. A nil stamp is an absent target,
        ;; not a request to synthesise `:rf/default`; surface the always-on
        ;; `:rf.error/no-frame-context`. Per Spec 002 §Frame target resolution.
        frame   (frame/require-frame-stamp!
                  frame :rf.ssr/hydrate {:where 'rf.ssr/hydrate!})
        payload (or payload
                    #?(:cljs (read-server-payload
                               (or element-id constants/payload-script-id))
                       :clj nil))]
    (when payload
      ;; The payload's `:rf/frame-id` is metadata and validation
      ;; evidence, NOT a no-opts target resolver. Validate it against the
      ;; explicit client target: a conflict is a structured mismatch (the
      ;; server hydrated a different frame than the client is installing
      ;; into), surfaced rather than silently picking a side.
      (validate-payload-frame-id! frame payload)
      ;; HOT PATH — seed app-db from the server's slice BEFORE first render.
      ;; `router/dispatch-sync!` is the owning-ns fn-form the `dispatch-sync`
      ;; macro itself calls through to (no call-site source-coord capture —
      ;; this is programmatic boot, not a hand-written call site). Requiring
      ;; the router directly keeps `boot` on the granular-require convention
      ;; the other ssr sub-namespaces follow (frame/events/trace, never
      ;; the `re-frame.core` public façade).
      (router/dispatch-sync! [:rf/hydrate payload] {:frame frame})
      ;; HOT PATH — post-render hash-mismatch detection. Symmetric with
      ;; the server's `:emit-hash?`-stamped `data-rf-render-hash` marker.
      (when render-tree-fn
        (hydrate/verify-hydration! frame (render-tree-fn))))
    payload))
