(ns day8.re-frame2-xray.filters.matcher
  "Pattern matching for Xray's IN/OUT filter pills.

  Per `tools/xray/spec/018-Event-Spine.md` §7 the filter system runs
  at the DATA layer (`:rf.xray/filtered-event-bundles` sub) — every consumer
  (event list, scrubber, Issues counter, palette verbs) reads the
  filtered list. The matcher in this ns is the pure data primitive
  the sub composes against the raw event-bundle list.

  ## Pattern syntax

  Spec/018 §7 calls out four supported pattern shapes; this matcher
  implements three first-class shapes (exact / prefix-glob / bare
  keyword, which is exact-OR-namespace) + the substring fallback:

  | Shape       | Example          | Matches                                   |
  |-------------|------------------|-------------------------------------------|
  | exact kw    | `:auth/login`    | event-id equal to the keyword             |
  | prefix glob | `:auth/*`        | event-id whose `(str id)` starts with `:auth/` |
  | bare kw     | `:auth`          | event-id `:auth` OR any event-id whose namespace = `\"auth\"` |
  | substring   | `/login`         | event-id whose `(str id)` contains `/login` |

  Patterns may be supplied as keywords (`:auth/login`, `:auth/*`) or as
  strings (`\":auth/*\"`, `\"auth/*\"`, `\"login\"`). Strings with a
  leading `:` are normalised to keywords first; a colon-less string
  ending in `*` is a prefix glob (`\"auth/*\"` behaves as `:auth/*`);
  any other colon-less string falls through to substring.

  ## Match composition (spec/018 §7)

      ACTIVE = (match-any-IN) AND NOT (match-any-OUT)

  - **No IN pills**  → show everything not blacklisted (OUT only).
  - **Some IN pills** → restrict to events matching ANY IN pattern,
    minus any OUT match.

  ## Why CLJC

  The matcher is pure data; JVM tests in
  `tools/xray/test/.../filters/matcher_cljs_test.cljc` exercise the shape
  without a CLJS runtime. The CLJS pill view + filtered-event-bundles sub
  compose against this ns at render-time."
  (:require [clojure.string :as str]))

;; ---- pattern compilation -------------------------------------------------

(defn- glob? [s]
  (str/ends-with? s "*"))

(defn- normalise-pattern
  "Coerce a pattern (keyword or string) into the canonical match shape.
  Returns
  `{:kind <:exact|:exact-or-ns|:prefix|:substring|:never> :pattern <data>}`.

  Per spec/018 §7 the supported pattern shapes are:

    | input form      | example          | kind         |
    |-----------------|------------------|--------------|
    | qualified kw    | `:auth/login`    | :exact       |
    | prefix glob     | `:auth/*`        | :prefix      |
    | bare kw         | `:auth`          | :exact-or-ns |
    | str with `:`    | `\":auth/*\"`    | (parses)     |
    | bare str + `*`  | `\"auth/*\"`     | :prefix      |
    | bare str        | `\"/login\"`     | :substring   |
    | empty / nil     |                  | :never       |

  ## Why a bare keyword is exact-OR-namespace (rf2-y8doi.27)

  A bare unqualified keyword is the one pill shape a user types for
  two different intents, and the dialog's own copy promises both:
  `:auth` reads as 'the `auth` namespace' to anyone who has seen a
  qualified event-id, and as 'this specific event-id' to anyone
  filtering an unqualified one. Compiling it to `:exact` alone made
  the dialog's `:auth` example match nothing at all — the L2 list
  silently emptied, which is the failure an inspector may not have.

  So a bare keyword matches EITHER reading: `:auth` matches the
  event-id `:auth` and every event-id whose namespace is `\"auth\"`
  (`:auth/login`), and nothing else — `:authors/x` is a different
  namespace and does not match. Spec/018 §7's example pill
  `[× :mouse-move]` still blocks `:mouse-move`; nothing dispatches
  under a `mouse-move` namespace, so the union costs it nothing.
  A qualified keyword (`:auth/login`) is unambiguous and stays
  `:exact`; the glob form (`:auth/*`) stays the way to say
  'namespace, and only namespace'."
  [pattern]
  (cond
    (keyword? pattern)
    (let [s (str pattern)]
      (cond
        ;; `:auth/*` → prefix `":auth/"`. Drop the trailing `*`.
        (glob? s)            {:kind :prefix :pattern (subs s 0 (dec (count s)))}
        (namespace pattern)  {:kind :exact :pattern pattern}
        :else                {:kind :exact-or-ns :pattern pattern}))

    (string? pattern)
    (cond
      (str/blank? pattern)
      ;; A blank pattern matches nothing — guard so a half-filled pill
      ;; doesn't silently match every event.
      {:kind :never :pattern nil}

      (str/starts-with? pattern ":")
      (recur (keyword (subs pattern 1)))

      ;; A colon-less glob is the same intent as the keyword glob —
      ;; the user dropped the `:` the event-id carries. Route it
      ;; through the keyword branch so `"auth/*"` and `:auth/*`
      ;; compile identically; without this it fell to `:substring`
      ;; and matched nothing, because no `(str event-id)` contains
      ;; a literal `*`.
      (glob? pattern)
      (recur (keyword pattern))

      :else
      {:kind :substring :pattern pattern})

    :else
    {:kind :never :pattern nil}))

;; ---- single-pill match ---------------------------------------------------

(defn match-event-id?
  "True iff `event-id` matches the compiled `pattern-spec` (output of
  `normalise-pattern`).

  - `:exact`       — keyword equality
  - `:exact-or-ns` — keyword equality OR `event-id`'s namespace equals
                     the bare pattern's name (`:auth` ⇒ `:auth` and
                     `:auth/login`, but not `:authors/x`)
  - `:prefix`      — `(str event-id)` starts with the prefix
  - `:substring`   — `(str event-id)` contains the substring
  - `:never`       — always false (blank / malformed pill)

  `event-id` may be nil (an unrouted event-bundle carrying no event); nil
  never matches."
  [event-id {:keys [kind pattern]}]
  (cond
    (nil? event-id) false
    (= :never kind) false
    (= :exact kind) (= event-id pattern)

    (= :exact-or-ns kind)
    ;; The namespace arm is guarded on `keyword?` because an event-id
    ;; is not required to be one — `(namespace 42)` throws, where the
    ;; `:prefix` / `:substring` arms below only ever `str` it.
    (or (= event-id pattern)
        (and (keyword? event-id)
             (= (namespace event-id) (name pattern))))

    (= :prefix kind)
    (let [s (str event-id)]
      (str/starts-with? s pattern))

    (= :substring kind)
    (str/includes? (str event-id) pattern)

    :else false))

(defn match-pill?
  "True iff the pill matches `event-id`. Pills are
  `{:pattern <kw-or-str>}` — event-id is the only scope.

  Returns false when the pill is malformed (missing pattern)."
  [{:keys [pattern]} event-id]
  (and (some? pattern)
       (match-event-id? event-id (normalise-pattern pattern))))

;; ---- event-bundle-level filtering --------------------------------------------

(defn- event-bundle-event-id
  "Pluck the event-id from an event-bundle — same shape `shell/event-id-of-
  event-bundle` plucks (`(first (:event event-bundle))`). Lifted here so the
  matcher stays a self-contained pure unit; the shell delegates."
  [event-bundle]
  (let [ev (:event event-bundle)]
    (when (vector? ev)
      (first ev))))

(defn event-bundle-matches?
  "True iff `event-bundle`'s event-id matches *any* pill in `pills`.

  - Empty / nil `pills` → false (no patterns to match against).
  - Otherwise → true on first pill match."
  [event-bundle pills]
  (boolean
    (when (seq pills)
      (let [event-id (event-bundle-event-id event-bundle)]
        (some #(match-pill? % event-id) pills)))))

(defn keep-event-bundle?
  "True iff `event-bundle` survives the active filter per spec/018 §7:

      keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)

  Event bundles that fail the keep test drop out of `:rf.xray/filtered-
  event-bundles`. The L2 event list, scrubber, Issues counter, palette
  verbs all read the filtered list — one filter, every consumer."
  [event-bundle {:keys [in out]}]
  (let [in-ok?  (or (empty? in)
                    (event-bundle-matches? event-bundle in))
        out-hit (event-bundle-matches? event-bundle out)]
    (and in-ok? (not out-hit))))

(defn filter-event-bundles
  "Apply `filters` to `event-bundles`, returning the surviving subseq in
  order. Pure — no I/O, no atoms read."
  [event-bundles filters]
  (filterv #(keep-event-bundle? % filters) event-bundles))

;; ---- frame-picker filter ------------------------------------------------

(defn keep-event-bundle-for-frame?
  "True iff `event-bundle`'s `:frame` matches the picker-selected
  `picker-frame`. nil / absent `picker-frame` means 'no frame filter
  active' — every event-bundle survives. Event bundles whose `:frame` slot is
  nil (the `:ungrouped` bucket from registry-time emits / lifecycle
  outside a drain) drop out when a frame filter is active.

  Per spec/018 §3 Frame dropdown: the picker is single-select and the
  L2 list MUST show only the picked frame's event-bundles. Filtering at
  the data layer keeps the virtualisation budget honest and the
  ribbon nav (`◀ ▶ ⏭`) walking the same surface every consumer reads.

  Pure data; JVM-runnable."
  [event-bundle picker-frame]
  (or (nil? picker-frame)
      (= picker-frame (:frame event-bundle))))

(defn filter-event-bundles-by-frame
  "Restrict `event-bundles` to those whose `:frame` matches `picker-frame`.
  nil `picker-frame` returns `event-bundles` unchanged. Pure — no I/O, no
  atoms read. Per spec/018 §3 Frame dropdown."
  [event-bundles picker-frame]
  (if (nil? picker-frame)
    event-bundles
    (filterv #(keep-event-bundle-for-frame? % picker-frame) event-bundles)))

(defn keep-event-bundle-for-view-scope?
  "True iff `event-bundle` is in the picker-selected VIEW SCOPE `scope-frame`.
  Like `keep-event-bundle-for-frame?` but the FRAMELESS
  `:ungrouped` pseudo-event-bundle (nil `:frame` — registry-time emits /
  lifecycle outside a drain) is frame-AGNOSTIC and always survives the
  scope: it has no frame to match, and whether it RENDERS is gated
  separately by the `show-ungrouped?` opt-in (`l2-event-bundle-visible?`).

  This is the view-scope analogue of `keep-event-bundle-for-frame?`: a view
  scope narrows to one host frame's events without swallowing the
  frame-agnostic bucket. Pure data; JVM-runnable."
  [event-bundle scope-frame]
  (or (nil? scope-frame)
      (nil? (:frame event-bundle))
      (= scope-frame (:frame event-bundle))))

(defn filter-event-bundles-by-view-scope
  "Restrict `event-bundles` to the picker-selected VIEW SCOPE `scope-frame`,
  preserving frameless `:ungrouped` pseudo-event-bundles. nil
  `scope-frame` returns `event-bundles` unchanged. Pure — no I/O, no atoms
  read. The L2 list + hidden-count baseline read THIS (not the strict
  `filter-event-bundles-by-frame`) so a defaulted view scope never drops the
  frame-agnostic bucket."
  [event-bundles scope-frame]
  (if (nil? scope-frame)
    event-bundles
    (filterv #(keep-event-bundle-for-view-scope? % scope-frame) event-bundles)))
