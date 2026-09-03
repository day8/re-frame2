(ns day8.re-frame2-xray.panels.local-render
  "Xray's ON-BOX local-render egress seam — the EP-0015 `:rf.egress/local-redacted`
  graduating consumer (rf2-t55hxg.12).

  ## What this is

  ONE projection seam every Xray panel routes a FRAME-SOURCED value through
  before it reaches the shared `views/edn-inspector`: App-DB Diff,
  Subscriptions / Reactive, Machine Inspector, Flow, reply-envelope, Event
  Detail. It resolves the value through the public record-level boundary
  primitive `re-frame.core/project-egress` under the named egress PROFILE
  for on-box dev-tool rendering.

  ## The default is `:rf.egress/local-redacted` (EP-0015 §10, issue 3)

  [spec/015-Data-Classification.md §Projection profiles] names six closed
  `:rf.egress/*` profiles. The on-box dev-UI default is
  **`:rf.egress/local-redacted`**: *suppress sensitive display by default;
  the local operator may still see large values* (Xray runs in the
  developer's own browser, against the developer's own app — Security.md
  permits on-box value inspection; the only thing the local-redacted
  default withholds is data the FRAME declared `:sensitive`, which a
  shoulder-surfer / screen-share / recorded-session must not leak).

  This is the EP-0015 issue-3 **graduation consumer** for the
  `:rf.egress/local-redacted` profile (the one remaining gap in
  [spec/015 §The graduation gate]): the profile was defined + unit-tested
  in `re-frame.projection` but no on-box dev tool named it as its render
  default. Xray is that tool — the named on-box-dev-tool consumer the
  graduation row requires. Routing the local panel render through it
  exercises the profile end-to-end.

  ## `:rf.egress/local-raw` is the per-(tool,frame) opt-in (EP-0015 issue 7)

  [spec/015 §Cross-tool visibility grain]: on-box visibility is per
  (tool, frame) — there is **no single process-global `show-sensitive?`
  user toggle**. The default is `:rf.egress/local-redacted`; revealing
  sensitive values is an explicit trusted-local OPERATOR ACT that flips
  the per-(tool,frame) grain to `:rf.egress/local-raw` (include sensitive
  AND large). `local-render-value`'s `raw?` arg carries that grain — the
  caller resolves it per (tool, frame); this seam does not own the toggle
  state.

  ## Why a frame-keyed projection (not a global redact)

  Egress policy is FRAME-SCOPED (EP-0002 / Spec 015 §Direct reads). A value
  is projected under the OBSERVED frame's own `:sensitive` / `:large`
  classification — the frame Xray is currently inspecting — never a
  borrowed or ambient one. The named frame is passed as the explicit
  `:frame` opt so the walk applies THAT frame's policy regardless of any
  ambient scope, exactly as `redact-graph-for-egress` (the off-box sibling,
  rf2-yjarv6) does.

  ## Fail-closed (the silent-leak this seam abolishes)

  `rf/project-egress` delegates to `rf/elide-wire-value`, which resolves its
  governing frame as `(or (:frame opts) (frame/resolve-current-frame))` — so
  a nil `:frame` opt (or no `:frame` at all) falls through to the AMBIENT
  dynamically-bound frame, applying THAT frame's (possibly empty) policy and
  shipping value-bearing fields RAW under a borrowed scope. The local-render
  default must not do that. So this seam first checks the named frame is LIVE
  (`rf/frame-ids`); when it is not (nil id, a destroyed / never-registered
  frame), it stamps a DEAD-FRAME SENTINEL as the `:frame` opt — a non-nil id
  that can never resolve to a live frame — so the underlying walker takes its
  UNRESOLVABLE-FRAME fail-closed branch (`frame/frame` returns nil for it)
  and redacts the whole value to `:rf/redacted` rather than borrow the
  ambient frame's marks. Stamping the sentinel (NOT omitting `:frame`) is the
  point: an absent / nil `:frame` opt is exactly the ambient-borrow path this
  seam abolishes (rf2-cra0nq, mirroring the off-box derivation-graph fix
  rf2-udkj69). (Under `:rf.egress/local-raw`'s explicit
  `:rf.size/include-sensitive? true` opt-out the walker ships the value
  raw even under the dead-frame sentinel — the operator has explicitly asked
  for it; the opt-out branch precedes the fail-closed redact.)

  ## The edn-inspector renders the sentinels natively

  A sensitive slot becomes the `:rf/redacted` keyword sentinel; a large
  slot (only under the redacted default if the caller does NOT pass
  `include-large?`) becomes the `{:rf.size/large-elided …}` marker. The
  shared `views/edn-inspector` already paints BOTH as first-class types
  (the muted `redacted` chip / the yellow `● large` chip) — so the panel
  needs no special-casing: it hands the PROJECTED value to the inspector
  and the sentinels render themselves.

  JVM-portable (`.cljc`) so the local-render contract is pinned by the JVM
  test corpus without a CLJS runtime."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The on-box render profiles.
;; ---------------------------------------------------------------------------

(def local-redacted-profile
  "The EP-0015 on-box dev-UI DEFAULT egress profile (Spec 015 §Projection
  profiles): suppress sensitive display; the local operator may still see
  large values (see `local-render-opts`). The single source of truth for
  the keyword so panels + tests never fork the spelling."
  :rf.egress/local-redacted)

(def local-raw-profile
  "The trusted-local OPT-IN egress profile (EP-0015 §Cross-tool visibility
  grain): include sensitive AND large. Flipped per (tool, frame) by an
  explicit operator act, never a process-global toggle."
  :rf.egress/local-raw)

(defn- fresh-dead-frame-sentinel
  "Mint a FRESH frame id that can never resolve to a live frame, stamped as the
  `:frame` opt to FAIL CLOSED a nil / unreachable egress frame WITHOUT borrowing
  the ambient scope. A new identity PER CALL — see §Why per-call below, which is
  the half a shared singleton got wrong.

  `rf/elide-wire-value` resolves its governing frame as `(or (:frame opts)
  (frame/resolve-current-frame))` — so a nil `:frame` opt (or no `:frame` at
  all) falls through to the AMBIENT dynamically-bound frame, applying that
  frame's (possibly empty) policy and shipping value-bearing fields RAW. An
  unreachable but NON-nil `:frame` opt instead takes the unresolvable-frame
  fail-closed branch (`frame/frame` returns nil ⇒ whole value redacted to
  `:rf/redacted`, unless the caller explicitly waived sensitive redaction).
  We therefore stamp this sentinel as the `:frame` opt when no live governing
  frame is known, so the walker fails closed on its own dead-frame branch
  rather than resolving an ambient frame.

  It has to be a value NO frame can be registered under, and a keyword is not
  one: `make-frame` validates no `:id` type and the registry is keyed by
  whatever id it is handed, so every keyword — however it is namespaced — is a
  public id an app can spell. Earlier passes used `::no-egress-frame`, which
  expands to the ordinary public keyword
  `:day8.re-frame2-xray.panels.local-render/no-egress-frame`; a live frame
  registered under that id made the stamp RESOLVE, took the walker's LIVE-frame
  branch, and shipped the value RAW under that frame's empty declaration
  registry — the fail-CLOSED stamp becoming fail-OPEN (rf2-ws60, first pass). A
  host object is an IDENTITY rather than a datum — nothing can *spell* an equal
  value — so no registration a caller writes by hand can match it, and
  `frame/frame` misses on it exactly as on a destroyed id.

  ## No public exit — the invariant, and the two passes that missed it

  A private identity is NECESSARY AND NOT SUFFICIENT. The second pass bound one
  `(Object.)` to a `^:private def` and claimed 'no frame can be registered under
  it' — but `local-render-opts` was PUBLIC and RETURNED that value in `:frame`
  for every nil / unreachable target, so a caller never had to manufacture an
  equal object: it was HANDED the identical one. Registering a frame under the
  value it received made every SUBSEQUENT unreachable projection resolve to that
  live frame and ship the secret raw — the same fail-OPEN outcome as the
  keyword, reached through the return value instead of through the spelling.

  The third pass minted a FRESH identity per call and kept the builder public.
  That closed the *durable* poisoning route and left an immediate one open: the
  caller registers a frame under `(:frame opts)` and then hands THAT SAME opts
  map back to `rf/project-egress`. Reproduced on the JVM lane at the merged
  commit, source untouched:
  `{:registered? true, :projected {:auth {:token \"audit-secret-jwt-9f3a\"}}}`.

  So the discriminating question is not 'is the definition private', nor 'is the
  identity fresh', but **does any PUBLIC fn RETURN a structure containing it**.
  Both sibling carriers answer NO and are clean for exactly that reason: in
  `day8.re-frame2-xray.egress` `egress-value` builds its opts map INLINE as the
  argument to the walker and returns the walker's result; in
  `re-frame.derivation.egress` `walk-opts` is bound inside `project-graph`'s
  `let` and `project-graph` returns the transformed graph. Neither ever hands an
  opts map to anyone.

  This namespace now answers NO the same way: `local-render-opts` is PRIVATE, so
  the only structures crossing the namespace boundary are the projected values
  `local-render-value` / `local-render-value-at` return. Nothing outside can
  obtain a sentinel to register a frame under, or an opts map to replay.

  ## Why per-call as well (defence in depth, and it is free)

  Freshness is no longer the invariant, but it is kept, because it costs a bare
  allocation on a branch that is already the cold one and it removes the
  DURABLE identity a future re-exposure of the builder would otherwise hand out.
  With both properties in place, a sentinel that somehow escaped would govern
  its own single projection and nothing else. Do not cache, intern, or hoist it,
  and do not make the builder public again.

  Mirrors the off-box derivation-graph fix (rf2-udkj69); applied to
  local-render by rf2-cra0nq. Third carrier of the sentinel class, alongside
  `day8.re-frame2-xray.egress/no-frame` (rf2-7htk7) and
  `re-frame.derivation.egress` (rf2-g1vu); this file is `.cljc` and IS read on
  the JVM (its test namespace runs under `clojure -M:test`), so the substitute
  takes the reader-conditional form rather than a bare `(js/Object.)`.

  Allocation note: the call sits on the UNREACHABLE branch of an `if`, so a
  live-frame render — the hot path — mints nothing."
  []
  #?(:clj (Object.) :cljs (js/Object.)))

(defn local-render-profile
  "Resolve the egress profile a local panel render should project under,
  given the per-(tool,frame) `raw?` grain. `true` ⇒ the trusted-local
  `:rf.egress/local-raw` opt-in; anything else ⇒ the
  `:rf.egress/local-redacted` default."
  [raw?]
  (if (true? raw?) local-raw-profile local-redacted-profile))

(defn- local-render-opts
  "Build the `rf/project-egress` opts map for an on-box local render of a
  value sourced from `frame-id`.

  **PRIVATE, and that is the load-bearing half of the rf2-ws60 fix — see
  §No public exit in `fresh-dead-frame-sentinel`.** This is the one structure
  that carries a dead-frame sentinel, so it must never leave the namespace:
  `local-render-value` / `local-render-value-at` build it and hand it straight
  to `rf/project-egress`, and a caller receives only the PROJECTED VALUE.

  - **profile** — `local-render-profile` resolves the named boundary from
    the per-(tool,frame) `raw?` grain (default `:rf.egress/local-redacted`).
  - **`:frame`** — ALWAYS stamped: the named `frame-id` when it is LIVE
    (`rf/frame-ids`); otherwise (nil id, a destroyed / never-registered
    frame) a FRESHLY MINTED dead-frame sentinel. We must NOT omit / leave
    `:frame` nil for an unreachable frame: an absent / nil `:frame` opt lets
    the walker fall through to the AMBIENT dynamically-bound frame
    (`frame/resolve-current-frame`) and ship value-bearing fields RAW under
    that borrowed frame's policy — the exact ambient-borrow leak this seam
    abolishes (rf2-cra0nq, mirroring rf2-udkj69). So the walker takes its
    unresolvable-frame fail-closed branch (redact the whole value) rather
    than borrow another frame's policy.

    **The opts map this returns must not escape the namespace, and the
    sentinel it carries is SINGLE-USE (rf2-ws60).** The three passes at
    this bug were a public keyword, a public shared private-def object,
    and a public per-call object; each stayed fail-OPEN because the
    caller could get its hands on the sentinel (or on this very map) and
    register a live frame under it. Privacy is what closes that, and
    freshness is what keeps any future escape harmless. Do not cache,
    intern, or hoist the sentinel, and do not make this fn public.
  - **`:rf.size/include-large? true`** — the on-box 'keep large' override
    (EP-0015 §10: `local-redacted` *suppresses sensitive display*; the
    local operator IS entitled to large values — Xray's own size-bounding
    is a display ergonomics concern, not a privacy one). Composition: the
    profile floor (`include-large? false`) is overlaid by this explicit
    `:rf.size/*` boolean (the override WINS — `re-frame.projection`
    §resolve-elision-opts). Under `:rf.egress/local-raw` the floor already
    includes large; the explicit overlay is a harmless no-op there.

  Pure / JVM-portable except for the live-frame probe, which is a pure read
  over the runtime frame registry."
  ([frame-id] (local-render-opts frame-id false))
  ([frame-id raw?]
   ;; A reachable (live) frame governs egress under its own policy; a nil /
   ;; unreachable frame stamps the dead-frame sentinel as the `:frame` opt so
   ;; `rf/elide-wire-value` takes its unresolvable-frame FAIL-CLOSED branch
   ;; (whole value ⇒ `:rf/redacted`). We must NOT leave the `:frame` opt
   ;; absent / nil here: that frameless path lets the walker fall through to
   ;; the AMBIENT dynamically-bound frame (`frame/resolve-current-frame`) and
   ;; ship value-bearing fields RAW under that frame's policy — the exact
   ;; ambient-borrow leak this seam abolishes (rf2-cra0nq, mirroring
   ;; rf2-udkj69).
   (let [reachable? (and (some? frame-id) (contains? (rf/frame-ids) frame-id))]
     {:rf.egress/profile      (local-render-profile raw?)
      :rf.size/include-large? true
      :frame                  (if reachable? frame-id (fresh-dead-frame-sentinel))})))

;; ---------------------------------------------------------------------------
;; The projection seam.
;; ---------------------------------------------------------------------------

(defn local-render-value
  "Project `v` for ON-BOX local rendering through `rf/project-egress` under
  the EP-0015 on-box dev-UI default `:rf.egress/local-redacted` (or
  `:rf.egress/local-raw` when the per-(tool,frame) `raw?` grain opts in).

  `frame-id` is the OBSERVED frame whose `:sensitive` / `:large`
  classification governs the projection (the frame Xray is currently
  inspecting). A sensitive-declared slot is replaced by the `:rf/redacted`
  sentinel; non-sensitive siblings + large values ride through (the
  `include-large?` overlay). Structure is preserved — only declared leaf
  slots are touched.

  Fail-closed: an unreachable `frame-id` (nil / destroyed / never
  registered) under the redacted default redacts the WHOLE value rather
  than ship it raw under no policy. Idempotent over the sentinels (a
  re-projection of an already-`:rf/redacted` value is a no-op — the
  sentinel is a non-matchable scalar).

  Returns the projected value, ready to hand to the shared
  `views/edn-inspector` (which renders the `:rf/redacted` / large markers
  as first-class chips)."
  ([v frame-id] (local-render-value v frame-id false))
  ([v frame-id raw?]
   (rf/project-egress v (local-render-opts frame-id raw?))))

(defn local-render-value-at
  "Like `local-render-value`, but for a value that sits at absolute app-db /
  runtime-db `path` (a SLICE egress'd in isolation, not the whole walked
  root). Threading `:path` lets `rf/project-egress` match the path-keyed
  `:sensitive` / `:large` declarations a bare-value walk (path `[]`) would
  miss — e.g. the per-instance resource declarations the resources registry
  LOWERS onto absolute runtime-db slot paths rooted at the entry's byte
  key-id (`[:rf.runtime/resources :entries <key-id> :data …]`, rf2-aw9cfs).
  This is the Resources tab's on-box per-slot egress seam (rf2-9zix0u), the
  path-aware sibling of `local-render-value` the App-DB tab uses.

  Same fail-closed + per-frame guarantees as `local-render-value` — they
  share `local-render-opts`, so an unreachable `frame-id` (nil / destroyed /
  never registered) stamps the dead-frame sentinel and redacts the whole
  slice rather than borrow the ambient frame's policy, and the projection
  applies the OBSERVED frame's own `:sensitive` / `:large` classification. The
  explicit `:path` composes with (never overrides) the profile floor + the
  `include-large?` overlay `local-render-opts` sets."
  ([v frame-id path] (local-render-value-at v frame-id path false))
  ([v frame-id path raw?]
   (rf/project-egress v (assoc (local-render-opts frame-id raw?)
                               :path (vec path)))))
