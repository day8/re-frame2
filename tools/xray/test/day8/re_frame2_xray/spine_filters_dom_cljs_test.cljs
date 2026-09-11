(ns day8.re-frame2-xray.spine-filters-dom-cljs-test
  "Browser-lane half of the per-event-id mute-filter tests (rf2-ikuwt),
  promoted out of `day8.re-frame2-xray.spine-filters-cljs-test` under
  rf2-r51p.

  WHY A SEPARATE NAMESPACE. The sibling keeps the pure reducers
  (`mute-event-id` / `unmute-event-id` / `clear`), the EDN round-trip
  and the cascade-composition walk — none of which touches a host. The
  `save!` / `load` round-trip, the persist-fx write-throughs and the
  hydrate-on-install lift need a real `window.localStorage`, and only a
  namespace ending `-dom-cljs-test` is ever loaded by the
  `:browser-test` build, whose `:ns-regexp` is `.*-dom-cljs-test$`.
  Sitting in the sibling file these rows executed in NEITHER lane:
  skipped under `:node-test` for want of storage (no jsdom in any
  dependency list), and never loaded by `:browser-test` at all. The
  file's LOCATION was the defect. The guard was not.

  THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, so the node
  build loads this namespace too and the overlap is deliberate (see the
  comment above `:browser-test` in `implementation/shadow-cljs.edn`).
  Moving a row here ADDS the browser lane; it does not take the row off
  the node one. `ls/available?` is what keeps the node run inert.

  THE SKIP BRANCH ASSERTS RATHER THAN VANISHING, so the node lane never
  holds a deftest with zero assertions — the hollow shape rf2-r51p
  exists to remove.

  These assertions had never executed in ANY lane before this namespace
  existed. A failure here is evidence arriving for the first time, not a
  regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; Mirrors the sibling's fixture. The raw-mute slate matters more here
  ;; than on node: the browser lane runs every namespace on ONE page, so
  ;; a leftover mute set would be in storage when the next namespace
  ;; hydrates.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn [] (spine-filters/clear-raw!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  ;; mirrors mount.cljs/ensure-xray-frame! — re-runs hydrate after the
  ;; frame is registered (preload-time install no-op'd).
  (spine-filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; save! / load round-trip
;; -------------------------------------------------------------------------

(deftest save-and-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (let [muted #{:auth/login :user/mouse-move}]
      (spine-filters/clear-raw!)
      (spine-filters/save! muted)
      (is (= muted (spine-filters/load))
          "browser-backed round-trip preserves the whole mute set"))))

;; -------------------------------------------------------------------------
;; Event handler wiring + persist fx
;; -------------------------------------------------------------------------

(deftest mute-event-id-event-writes-slot-and-persists
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (spine-filters/clear-raw!)
      (xray-setup!)
      (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
      (is (= #{:user/mouse-move}
             (frame-sub [:rf.xray/muted-event-ids])))
      (is (= 1 (frame-sub [:rf.xray/muted-event-ids-count])))
      (is (= #{:user/mouse-move}
             (spine-filters/load))
          "mute round-trips to localStorage"))))

(deftest unmute-event-id-event-clears-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (spine-filters/clear-raw!)
      (xray-setup!)
      (frame-dispatch [:rf.xray/mute-event-id :user/mouse-move])
      (frame-dispatch [:rf.xray/mute-event-id :user/scroll])
      (frame-dispatch [:rf.xray/unmute-event-id :user/scroll])
      (is (= #{:user/mouse-move}
             (frame-sub [:rf.xray/muted-event-ids])))
      (is (= #{:user/mouse-move} (spine-filters/load))
          "unmute persists the new set"))))

(deftest clear-muted-event-ids-drops-every-entry
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (spine-filters/clear-raw!)
      (xray-setup!)
      (frame-dispatch [:rf.xray/mute-event-id :a])
      (frame-dispatch [:rf.xray/mute-event-id :b])
      ;; Precondition, not decoration: `(= #{} (load))` passes on a
      ;; silently no-op storage, where nothing was ever written. Proving
      ;; the two mutes persisted FIRST is what makes the empty read
      ;; below evidence that `clear` cleared something.
      (is (= #{:a :b} (spine-filters/load))
          "precondition: both mutes really did persist")
      (frame-dispatch [:rf.xray/clear-muted-event-ids])
      (is (= #{} (frame-sub [:rf.xray/muted-event-ids])))
      (is (= #{} (spine-filters/load))
          "clear drops every persisted entry"))))

;; -------------------------------------------------------------------------
;; Hydration
;; -------------------------------------------------------------------------

(deftest hydrate-lifts-localstorage-into-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      ;; Pre-seed localStorage BEFORE registry install so hydrate-on-mount
      ;; lifts the value.
      (spine-filters/save! #{:auth/login :user/mouse-move})
      (registry/reset-for-test!)
      (xray-setup!)
      (is (= #{:auth/login :user/mouse-move}
             (frame-sub [:rf.xray/muted-event-ids]))
          "hydrate lifted the persisted set into the slot")
      (spine-filters/clear-raw!))))
