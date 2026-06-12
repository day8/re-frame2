(ns re-frame.story.realm
  "The ONE place Story makes the EP-0013 \"stamp only a non-default realm\"
  decision — the absent-key rule (Spec-Schemas §`:rf/realm`, EP-0013 issue 2):
  absence of a realm key means THE DEFAULT realm.

  ## Why this ns exists (rf2-c6armm.12)

  Three Story paths independently needed to answer \"does this frame's runtime
  realm get carried, and if so under what key?\":

    - the RECORDER (`re-frame.story.recorder`) stamps a captured recording's
      target frame under `:rf.realm/id` (`realm-stamp-key`);
    - PLAY EXPORT (`re-frame.story.recorder.play-export`) carries the same
      `:rf.realm/id` stamp onto the emitted play-script map;
    - the REPLAY TARGET (`re-frame.story.play.runner-events`) carries the realm
      onto the `{:realm r :frame v}` address it replays against (under `:realm`,
      the address-map key — a DIFFERENT key, same predicate).

  Each had repeated the literal `(and (some? realm) (not= realm
  :rf.realm/default))` predicate inline. EP-0013 realm addressing is still
  evolving, so a predicate repeated across recorder / export / replay is a
  drift hazard — a future refinement of \"which realms get stamped\" would have
  to find and update three sites. Centralising the decision here keeps it ONE
  edit.

  Pure data → data; no `re-frame.core` dependency (the live `frame-realm` read
  stays at each call site — this ns only decides what to do with the value)."
  (:require [re-frame.realm :as realm]))

(def ^:const realm-stamp-key
  "The reserved app-value key a recording stamps its target frame's runtime
  realm under (EP-0013). Matches the framework's reserved `:rf.realm/id`
  spelling (Spec-Schemas §`:rf/realm`) so a stamped recording reads the way
  the realm-targeted query forms (`rf/registrations {:realm …}`) spell it."
  :rf.realm/id)

(defn non-default-realm
  "Return `realm-id` verbatim when it is a realm a Story path should CARRY, or
  nil when nothing should be carried. The single expression of the EP-0013
  absent-key rule for Story.

  Returns nil for:

    - `nil` — an unknown / destroyed frame (`rf/frame-realm` returns nil), or
              no realm resolvable;
    - `re-frame.realm/default-realm-id` (`:rf.realm/default`) — a single-realm
              / default-realm frame. Absence IS the default, so nothing is
              carried and the recording / target round-trips unchanged (zero
              ceremony).

  Returns the realm id verbatim for any other (constructed) realm so replay can
  verify it targets the same realm the recording was captured in."
  [realm-id]
  (when (and (some? realm-id)
             (not= realm-id realm/default-realm-id))
    realm-id))

(defn assoc-realm
  "Pure LOW-LEVEL stamper: assoc `realm-id` onto `m` under `k`, or leave `m`
  UNTOUCHED when `realm-id` is nil. Guards ONLY nil — a caller that wants the
  absent-key default-realm reduction reduces through `non-default-realm` FIRST
  (the two-layer split the recorder documents: `start` is the low-level
  stamper; `start-recording!` reduces through the predicate before calling it).

  `k` defaults to `realm-stamp-key` (`:rf.realm/id`, the recording-stamp key);
  the replay-target path passes `:realm` (the address-map key). The SAME
  nil-guarded assoc, two target keys."
  ([m realm-id] (assoc-realm m realm-stamp-key realm-id))
  ([m k realm-id]
   (cond-> m
     (some? realm-id) (assoc k realm-id))))

(defn stamp-realm
  "Pure: the COMBINED absent-key stamp — reduce a live `realm-id` (what
  `rf/frame-realm` returned) through `non-default-realm`, then `assoc-realm`
  the result under `k`. So a nil / default realm leaves `m` untouched and any
  constructed realm assocs its id. The one-call form play export + replay-target
  reach for (they hold a raw live value, not a pre-reduced stamp); the recorder
  keeps the split form because its `start` layer is deliberately low-level."
  ([m realm-id] (stamp-realm m realm-stamp-key realm-id))
  ([m k realm-id]
   (assoc-realm m k (non-default-realm realm-id))))
