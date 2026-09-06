(ns re-frame.ssr.boot
  "Client-side hydration boot helpers.

  `hydrate!` preserves the required read → hydrate → verify ordering. It
  dispatches synchronously before the host mounts, then optionally hashes a
  caller-supplied pure render-tree computation. It does not inspect or own the
  DOM mount. Hosts that must verify a substrate-mutated or asynchronous tree
  perform the hydrate and verify steps separately.

  `read-server-payload` is CLJS-only and reads the pinned `__rf_payload`
  element. `hydrate!` is platform-neutral when given an explicit payload."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.router :as rf.router]
            [re-frame.ssr.hydrate :as rf.ssr.hydrate]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.trace :as rf.trace]
            ;; `constants` + `cljs.reader` are only used by the CLJS-only
            ;; `read-server-payload` (DOM read); require them on CLJS so a
            ;; JVM lint of this `.cljc` doesn't flag them unused.
            #?(:cljs [re-frame.ssr.constants :as rf.ssr.constants])
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
             (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
    (dispatch-error-record!
      {:error      :rf.error/malformed-hydration-payload
       :frame      nil
       :time       (rf.interop/now-ms)
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
     ([] (read-server-payload rf.ssr.constants/payload-script-id))
     ([element-id]
      (when-let [el (.getElementById js/document element-id)]
        (try
          (reader/read-string (.-textContent el))
          (catch :default e
            (let [reason (str "the __rf_payload hydration script "
                              "did not parse as EDN; treating the "
                              "page as client-only (no hydration). "
                              (ex-message e))]
              (when rf.interop/debug-enabled?
                (rf.trace/emit-error! :rf.error/malformed-hydration-payload
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
      (let [ex   (rf.error/thrown-ex-info
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
        (when rf.interop/debug-enabled?
          (rf.trace/emit-error! :rf.error/hydration-frame-id-mismatch data))
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
                 SYNCHRONOUSLY, immediately after `:rf/hydrate`, BEFORE
                 the host's own render, and UNDER the target frame's scope —
                 so the documented `((rf/view :app/root))` idiom, whose view
                 derefs a frame-scoped `subscribe`, resolves the frame the
                 same way the server render does inside `(with-frame f …)`.
                 See the ns docstring §The verify
                 contract for why hashing the pure client tree pre-mount is
                 equivalent to hashing the mounted tree (a re-frame2 view is
                 a pure fn of the just-hydrated app-db). The helper does not
                 — and cannot — observe the host's DOM mount;
                 `:render-tree-fn` is a PURE client-tree computation, not a
                 \"read back what was mounted\" hook. When `:render-tree-fn`
                 is omitted the verify step is skipped (the host opts out of
                 hash-mismatch detection, or runs `verify-hydration!`
                 itself at its own render site).

                 **The render-tree hash is HICCUP-TIER-ONLY, and the tier is
                 keyed by RENDER-TREE REPRESENTATION — not by adapter brand**
                 (Spec 011 §Hydration-mismatch detection). Pass
                 `:render-tree-fn` when, and only when, the client render is a
                 hashable DATA tree:

                   • **Hashable data render-tree — Reagent and Reagent-slim.**
                     A view is a pure fn returning hiccup, so
                     `((rf/view :app/root))` yields the tree this step hashes.
                     That is the boot: `ssr/hydrate!` WITH `:render-tree-fn`,
                     then the adapter's own render. The
                     `:ssr {:on-mismatch :hard-error}` escalation belongs to
                     this tier alone.

                   • **React-element root — NATIVE UIx.** A native UIx view's
                     render-fn returns a React ELEMENT, not a hiccup data tree,
                     so there is NOTHING to hash and it must NOT pass
                     `:render-tree-fn`: call `ssr/hydrate!` without one. Its
                     mismatch reporting comes from React-native ADOPTION
                     instead, and only on the shared React-hook hydrate path —
                     mount through the Spec 006 client entry
                     `re-frame.substrate.adapter/render` with `{:hydrate? true}`
                     and the framework installs the reporter, surfacing
                     divergence as `:rf.ssr/hydration-mismatch` (`:where`
                     `re-frame.substrate.spine/make-render`). Hydrating via
                     `uix.dom/hydrate-root` directly bypasses it.

                   • **React-element root — HICASSO.** A Hicasso root hands
                     React an element rather than a hashable data tree, so
                     likewise NO `:render-tree-fn`: call `ssr/hydrate!` without
                     one, then hydrate the root through `h/hydrate!`, which
                     verifies by the same React-native adoption.

                 React-native adoption reports the divergences React itself
                 recovers from — text content, or a missing / extra / wrong-type
                 element — through the root's `onRecoverableError`; it does not
                 catch attribute-only drift, and it carries no `:hard-error`
                 escalation, because React has already patched the DOM by the
                 time the callback fires.

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
                      ONCE, SYNCHRONOUSLY, immediately after `:rf/hydrate`,
                      before the host's own render, and UNDER the `:frame`
                      scope (so a fn that calls a frame-scoped subscribing
                      view — the `((rf/view :app/root))` idiom — resolves;
                      a pure client-tree computation — see step 3). Omit to
                      skip verification.
    :element-id     — (CLJS, optional) override the payload `<script>` id
                      to read when `:payload` is omitted. Default the
                      pinned `__rf_payload`.
    :container      — (CLJS, optional) this root's container element. When
                      supplied, preflight DISCOVERS the root's manifest
                      positionally (the container's immediately following
                      element sibling) and validates it; no manifest there
                      is `:rf.error/root-manifest-invalid`.
    :manifest       — (optional) an explicit Root Manifest v1, validated in
                      place of discovery. The JVM / test path.
    :root-id        — (optional) this root's id, recorded as the payload's
                      installer and named in a conflict. Defaults to the
                      manifest's `:root-id` when a manifest was resolved.

  **Install is idempotent across the page's roots.** A page is N roots
  referencing M frames, and M is routinely smaller than N — several roots
  hydrate one frame, and each of them boots off the same page-wide
  `__rf_payload`. The FIRST `hydrate!` for a payload id installs it; a
  later one with the SAME payload finds it live and does not re-seed, so a
  sibling root booting second cannot silently discard what happened after
  the first one seeded (per [004C §6], ratified). A later call carrying a
  DIFFERENT payload for that id is the exception and fails loud with
  `:rf.error/frame-payload-conflict` — before any install, leaving the
  live payload and the roots using it untouched.

  Returns the payload that was applied (or `nil` on a client-only first
  load) so the caller can branch on \"was this server-rendered?\" without
  re-reading the DOM. A no-op second install still returns the payload —
  the page WAS server-rendered, whichever root got there first.

  Example (Reagent client boot):

      #?(:cljs
         (defn ^:export run []
           (rf/init! reagent-adapter/adapter)
           ;; hydrate! seeds app-db AND verifies synchronously (computing
           ;; the client tree via render-tree-fn) BEFORE the host mounts.
           ;; The hydration target is supplied explicitly via `:frame`, and
           ;; hydrate! calls render-tree-fn UNDER that frame's scope — so the
           ;; view's `subscribe` resolves without a manual `with-frame` wrap.
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
  (the re-frame2 norm — Reagent / UIx all qualify)."
  ;; A nil frame is an absent target, never an implicit `:rf/default`.
  [{:keys [frame payload render-tree-fn element-id manifest container root-id]}]
  ;; `element-id` is consumed only by the CLJS DOM read; discard-bind it so
  ;; a JVM lint of this `.cljc` doesn't flag it as an unused
  ;; `:clj`-expansion binding (the read itself stays CLJS-only).
  (let [_       element-id
        ;; The client hydration target is supplied explicitly. A nil stamp is an absent target,
        ;; not a request to synthesise `:rf/default`; surface the always-on
        ;; `:rf.error/no-frame-context`. Per Spec 002 §Frame target resolution.
        frame   (rf.frame/require-frame-stamp!
                  frame :rf.ssr/hydrate {:where 'rf.ssr/hydrate!})
        payload (or payload
                    #?(:cljs (read-server-payload
                               (or element-id rf.ssr.constants/payload-script-id))
                       :clj nil))]
    (when payload
      ;; The payload's `:rf/frame-id` is metadata and validation
      ;; evidence, NOT a no-opts target resolver. Validate it against the
      ;; explicit client target: a conflict is a structured mismatch (the
      ;; server hydrated a different frame than the client is installing
      ;; into), surfaced rather than silently picking a side.
      (validate-payload-frame-id! frame payload)
      ;; PREFLIGHT (S5) — manifest discovery/validation -> payload install
      ;; decision, BEFORE anything is seeded (004C §10). The frame-id
      ;; validation above runs FIRST: a payload rendered for a different
      ;; frame is rejected outright and must never reach the ledger, or a
      ;; misdirected payload would claim the target's payload id.
      ;;
      ;; A page is N roots referencing M frames, and M is routinely
      ;; smaller than N — several roots hydrate one frame and every one of
      ;; them boots off the same page-wide `__rf_payload`. `:install` means
      ;; this root is the first to reference the payload; `:already-installed`
      ;; means a sibling root already seeded exactly this content, and
      ;; re-seeding would discard everything that happened since (004C §6's
      ;; ratified "later roots find it live and do not re-seed"). A
      ;; DIFFERENT payload under the same id throws before any install.
      (let [{:keys [decision claim]}
            (rf.ssr.install/preflight! 'rf.ssr/hydrate!
                                {:payload    payload
                                 :payload-id frame
                                 :root-id    root-id
                                 :manifest   manifest
                                 :container  container})]
        ;; The whole seed-and-verify step is what a later root skips. It is
        ;; one step, not two: with nothing newly installed there is no new
        ;; server slice to verify against, and the payload's `:rf/render-hash`
        ;; covers the WHOLE server-rendered body — hashing a second root's
        ;; own subtree against it would manufacture a mismatch that says
        ;; nothing about either root. Per-root structural agreement is the
        ;; manifest's `:render-fingerprint` fact, not this hash.
        (when (= :install decision)
          ;; `router/dispatch-sync!` is the owning-ns fn-form the `dispatch-sync`
          ;; macro itself calls through to (no call-site source-coord capture —
          ;; this is programmatic boot, not a hand-written call site). Requiring
          ;; the router directly keeps `boot` on the granular-require convention
          ;; the other ssr sub-namespaces follow (frame/events/trace, never
          ;; the `re-frame.core` public façade).
          ;; THE CLAIM IS TRANSACTIONAL OVER THE SEED (Spec 011 §Failed-root
          ;; isolation). `preflight!` has already RECORDED this root's claim,
          ;; and a claim whose seed does not land is worse than no claim at
          ;; all: the next root referencing that payload reads
          ;; `:already-installed` — a legitimate verdict — and skips its own
          ;; install. The page then runs a frame nobody ever hydrated, with
          ;; no error anywhere, because nothing failed loudly. So the claim
          ;; is released unless the seed provably landed.
          ;;
          ;; `dispatch-sync!` does NOT report that for us. Dispatching into
          ;; an absent or destroyed frame is a NO-OP, not a throw — the
          ;; router reports it on its own always-on axis and returns
          ;; normally. So the condition is checked directly, and the check
          ;; is the frame's INCARNATION token: the seed landed only if the
          ;; exact incarnation that was live when we claimed is still live
          ;; now. A nil token (the frame was never there) is not live, so an
          ;; absent frame releases too; a destroy-and-recreate during the
          ;; dispatch yields a different token and also releases. This is
          ;; the same pinned-token admission rule a cold `reg-flow` uses to
          ;; keep a write off a dead or superseded incarnation.
          ;;
          ;; The transaction ends HERE, at the seed — the claim covers the
          ;; INSTALL, not the whole root boot. Once `:rf/hydrate` commits the
          ;; payload IS installed and siblings must keep finding it live;
          ;; releasing on a LATER failure (the verify below, or the host's
          ;; own mount) would invite a sibling to re-seed and silently reset
          ;; whatever ran in between — the precise harm the ledger exists to
          ;; prevent. A root may fail after a successful install; its payload
          ;; does not thereby become uninstalled.
          ;;
          ;; Unconditional, never `debug-enabled?`-gated: a root can fail in
          ;; production, so the claim must be released in production.
          (let [incarnation (rf.frame/frame-incarnation-token frame)]
            (rf.router/dispatch-sync! [:rf/hydrate payload] {:frame frame})
            (if-not (rf.frame/frame-incarnation-live? frame incarnation)
              (rf.ssr.install/release-claim! frame claim)
              ;; HOT PATH — post-render hash-mismatch detection. Symmetric with
              ;; the server's `:render-hash`-stamped `data-rf-render-hash` marker.
              ;; Runs only on a landed seed: verifying a client tree against a
              ;; server hash the frame never received compares nothing.
              ;;
              ;; Call `render-tree-fn` UNDER the target frame's scope (rf2-0vk7b). The
              ;; documented idiom `(fn [] ((rf/view :app/root)))` computes the client
              ;; tree by CALLING a registered view, whose reg-view-injected `subscribe`
              ;; resolves the ambient frame via the carried invariant. `hydrate!` already
              ;; resolved `frame` and dispatched `:rf/hydrate` into it, so — mirroring
              ;; the server render, which wraps `((rf/view :app/root))` in
              ;; `(with-frame f …)` — it pins that same frame here. Without the pin the
              ;; view's `subscribe` runs under no scope and raises
              ;; `:rf.error/no-frame-context`, aborting client boot. `bind-fn` re-binds
              ;; `*current-frame*` for the one call; a `render-tree-fn` that establishes
              ;; its own scope (or ignores it — a plain-hiccup fn) is unaffected.
              (when render-tree-fn
                (rf.ssr.hydrate/verify-hydration! frame ((rf.frame/bind-fn frame render-tree-fn)))))))))
    payload))

;; ---------------------------------------------------------------------------
;; Failed-root isolation (S5) — Spec 011 §Failed-root isolation
;; ---------------------------------------------------------------------------

(defn- exception-message [t]
  (or #?(:clj (.getMessage ^Throwable t) :cljs (.-message t))
      (str t)))

(defn report-root-boot-failed!
  "Report one CONTAINED root-boot failure and -> nil.

  The isolation boundary absorbs the throw so the page's other roots keep
  booting; this is the record that keeps the absorption from being
  SILENT. It is a genuinely new fact, not a re-report of the underlying
  error: the cause says *what* broke, this says **this root is not
  running and the page is live without it** — the thing an operator acts
  on.

  Two channels, per the `:rf.error/malformed-hydration-payload`
  precedent this mirrors (a hydration-boot failure the runtime absorbs
  rather than rethrows):

  - the **always-on** error-emit record, via the published
    `:error-emit/dispatch-error-record` hook. This is the load-bearing
    one. A root can fail in PRODUCTION, so its containment must be
    observable in production — the record survives `:advanced` +
    `goog.DEBUG=false` and reaches an off-box shipper.
  - a dev trace, `interop/debug-enabled?`-gated, for the local devtools
    stream.

  The always-on emit is deliberately OUTSIDE the debug gate. A page that
  quietly runs with N-1 roots and no signal is the failure mode this
  whole contract exists to prevent, so the signal cannot be dev-only."
  [{:keys [where frame root-id phase exception]}]
  (let [reason (str "root " (pr-str root-id) " failed to boot during "
                    (name phase) " and was ISOLATED: the page's other "
                    "roots hydrated and are running. This root is not "
                    "mounted and will not respond to dispatch. "
                    (exception-message exception))
        record {:error     :rf.error/root-boot-failed
                :frame     frame
                :time      (rf.interop/now-ms)
                :where     where
                :root-id   root-id
                :phase     phase
                :reason    reason
                :exception exception
                :recovery  :warned-and-continued}]
    (when rf.interop/debug-enabled?
      (rf.trace/emit-error! :rf.error/root-boot-failed (dissoc record :error :time)))
    (when-let [dispatch-error-record!
               (rf.late-bind/get-fn :error-emit/dispatch-error-record)]
      (dispatch-error-record! record)))
  nil)

(defn- boot-one-root!
  "Boot ONE root inside the isolation boundary -> its outcome map.

  The boundary spans the root's WHOLE boot — preflight, hydrate, and the
  host's own `:mount-fn` — because a root that hydrates but cannot mount
  is just as dead to the page as one that never hydrated, and isolating
  only half of that would leave the other half able to take the page
  down."
  [{:keys [root-id mount-fn] :as opts}]
  ;; `phase` is set from the ONE fact that distinguishes the halves: the
  ;; mount can only run after hydrate returned, so a throw seen while it
  ;; still reads `:hydrate` came from the hydrate half. It rides the
  ;; failure record so an operator reading "root :page/cart failed" knows
  ;; whether the frame was seeded before it died.
  (let [phase (volatile! :hydrate)]
    (try
      (let [payload (hydrate! (dissoc opts :mount-fn))]
        (vreset! phase :mount)
        (when mount-fn (mount-fn))
        {:root-id root-id :status :hydrated :payload payload})
      (catch #?(:clj Throwable :cljs :default) t
        (report-root-boot-failed!
         {:where     'rf.ssr/hydrate-page!
          :frame     (:frame opts)
          :root-id   root-id
          :phase     @phase
          :exception t})
        {:root-id root-id :status :failed :error t}))))

(defn hydrate-page!
  "Boot a page of N roots with **per-root failure isolation** -> a vector
  of per-root outcomes, in the order the roots were supplied.

  Per [Spec 011 §Failed-root isolation]: **a page is N roots, and one of
  them failing must not stop the others from hydrating and running.**
  Every root is booted inside its own boundary, so a throw from one is
  contained, reported, and the page moves on to the next.

  `roots` is a collection of per-root opt maps. Each is the map
  `hydrate!` takes (`:frame`, `:payload`, `:container`, `:manifest`,
  `:root-id`, `:render-tree-fn`, `:element-id`), plus:

    :mount-fn — (optional) a 0-arity fn that mounts this root, run
                immediately after its hydrate, INSIDE the boundary. Pass
                the host mount here rather than looping outside: a root
                whose mount throws is exactly as dead as one whose
                hydrate threw, and only a mount inside the boundary is
                actually isolated.

  Each outcome is

      {:root-id … :status :hydrated :payload …}   ; or
      {:root-id … :status :failed   :error <the throwable>}

  Outcomes come back in input order, so a caller correlates them
  positionally even for a root that failed before its id could be read
  from a manifest (`:root-id` is then whatever was passed in, possibly
  nil).

  **What a failed root leaves behind.** Nothing that can harm a sibling:

  - Failed in PREFLIGHT (no manifest, a manifest outside the schema
    family, a payload conflict) — nothing at all. The throw happens
    before any claim, so the ledger is untouched.
  - Failed during the SEED — no claim. `hydrate!` releases the exact
    claim it made, so the payload id is returned to unclaimed and the
    next root referencing it gets a true `:install` rather than a
    poisoned `:already-installed`.
  - Failed AFTER the seed committed (verify, or the host's mount) — the
    installed payload, deliberately. That root is dead, but its frame is
    correctly hydrated and any sibling root sharing it keeps running
    against live, correct state.

  In every case the root's failure is REPORTED, not swallowed: an
  always-on `:rf.error/root-boot-failed` record per failed root (see
  `report-root-boot-failed!`), plus the throwable itself in the outcome.

  This is isolation, not recovery. There is no retry, no supervision, no
  fallback render, and no attempt to make a failed root work — a failed
  root stays failed. The single guarantee is that it stays failed
  ALONE.

  Example (Reagent, three roots on one server-rendered page):

      (let [outcomes (ssr/hydrate-page!
                       (for [[rid container] page-roots]
                         {:frame     :app/main
                          :root-id   rid
                          :container container
                          :mount-fn  #(rdc/render (rdc/create-root container)
                                        [rf/frame-provider {:frame :app/main}
                                         [(rf/view :app/root)]])}))]
        (when-let [failed (seq (filter #(= :failed (:status %)) outcomes))]
          (js/console.warn \"roots did not boot:\" (pr-str (map :root-id failed)))))"
  [roots]
  (mapv boot-one-root! roots))
