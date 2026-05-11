# Regions — single-region machines and parallel regions

## When to load

Reach for this leaf when the feature has more than one orthogonal axis of concern (data + form + mode; connection + auth; filter + feed) and you want one machine to drive them all. For the basic machine declaration, see `reg-machine.md`; for tags (the per-axis query shape that pairs with parallel regions), see `tags.md`.

## Two region shapes

A "region" in re-frame2 has two distinct meanings depending on context:

1. **Single-region machine** — an ordinary `rf/reg-machine` whose declaration is just `{:initial ... :states ...}`. The "region" framing applies when you used a state machine instead of a slice **because the state-keyword IS the lifecycle phase** (Pattern-RemoteData's `:idle / :loading / :fetching / :loaded / :error`, Pattern-Forms' `:neutral / :incorrect / :submitting / :correct`). This is the canonical refactor target when an app's `:status` field is being driven by hand.
2. **Parallel regions** — a single machine whose `:type :parallel` declaration carries N concurrent regions, each with its own `:initial` and `:states`. Broadcast events flow to every region; each region advances independently. `:data` is shared. `:tags` is the **union** across all active regions.

## Single-region machine — the canonical shape

```clojure
(def tags-machine
  {:initial :idle
   :data    {:tags [] :error nil :loaded-at nil :attempt 0}

   :actions
   {:set-tags
    (fn action-set-tags [data [_ {:keys [tags now]}]]
      {:data (assoc data :tags (vec tags) :error nil :loaded-at now)})}

   :states
   {:idle     {:tags #{:tags/idle}
               :on   {:fetch-started :loading}}

    :loading  {:tags #{:tags/loading :tags/in-flight}
               :on   {:fetch-succeeded {:target :loaded :action :set-tags}
                      :fetch-failed    :error}}

    :loaded   {:tags #{:tags/loaded}
               :on   {:fetch-started :fetching}}      ;; revalidate; don't blank

    :fetching {:tags #{:tags/fetching :tags/in-flight :tags/loaded}
               :on   {:fetch-succeeded {:target :loaded :action :set-tags}
                      :fetch-failed    :error}}

    :error    {:tags #{:tags/error}
               :on   {:fetch-started :loading}}}})

(rf/reg-machine :realworld/tags tags-machine)
```

Verbatim from `examples/reagent/realworld/tags.cljs:71-142`. The slice's separate `:status` keyword disappears; the state IS the status. The view consumes `:tags/in-flight` via `(rf/has-tag? :realworld/tags :tags/in-flight)` instead of a hand-rolled `(or (= :loading status) (= :fetching status))`.

## Parallel regions — the canonical declaration

```clojure
(def nine-states-machine
  {:type :parallel
   :data {:items [] :error nil :archived-at nil}     ;; shared across regions

   :guards   {:empty?     (fn [data _] (zero? (count (:items data))))
              :too-many?  (fn [data _] (> (count (:items data)) 7))}

   :actions  {:set-items  (fn [data [_ {:keys [items]}]]
                            {:data (assoc data :items (vec items))})}

   :regions
   {:data    {:initial :nothing
              :states  {:nothing   {:tags #{:data/nothing}
                                    :on   {:fetch-started :loading}}
                        :loading   {:tags #{:data/loading}
                                    :on   {:fetch-succeeded {:target :resolving
                                                             :action :set-items}}}
                        :resolving {:always [{:guard :empty?    :target :empty}
                                             {:guard :too-many? :target :too-many}
                                             {:target :some}]}
                        :empty     {:tags #{:data/empty}}
                        :some      {:tags #{:data/some}}
                        :too-many  {:tags #{:data/too-many}}}}

    :form    {:initial :neutral
              :states  {:neutral   {:tags #{:form/neutral}
                                    :on   {:submit-valid   :correct
                                           :submit-invalid :incorrect}}
                        :incorrect {:tags #{:form/invalid}}
                        :correct   {:tags #{:form/success}}}}

    :mode    {:initial :active
              :states  {:active    {:tags #{:mode/active}
                                    :on   {:archive :done}}
                        :done      {:tags #{:mode/done :mode/read-only}}}}}})

(rf/reg-machine :ui/nine-states nine-states-machine)
```

From `examples/reagent/nine_states/core.cljs:197-333`. Three orthogonal axes, one machine. `(rf/dispatch [:ui/nine-states [:fetch-started]])` reaches **every** region; the `:data` region advances; the `:form` and `:mode` regions ignore the event (their `:on` tables don't list it). The runtime is validated by `validate-parallel!` (`implementation/machines/src/re_frame/machines.cljc:1725`).

## Snapshot shape with parallel regions

For a parallel machine the snapshot's `:state` is a **map** keyed by region-name:

```clojure
;; Single-region:    {:state :loading                       :data {...} :tags #{...}}
;; Parallel-region:  {:state {:data :loading :form :neutral :mode :active}
;;                    :data  {...}
;;                    :tags  #{:data/loading :form/neutral :mode/active}}
```

`:data` is shared across regions (same map). `:tags` is the union of every active state's `:tags` set across every region. Per Spec 005 §Parallel regions §Snapshot shape and `machines.cljc:295` (Stage 2 broadens the tag union to cover all active regions).

## Common gotchas

- **`:type :parallel` is mutually exclusive with root `:initial` / `:states`.** Use one or the other at the top level. Registration throws `:rf.error/machine-parallel-bad-shape` if both are present (`machines.cljc:1746`).
- **Each region needs its own `:initial`.** Region bodies are themselves transition tables; a missing `:initial` keyword is a registration-time error (`machines.cljc:1762`).
- **Region names are keywords.** `:regions {:data {...} :form {...}}` — not strings, not symbols. Validated at registration (`machines.cljc:1750`).
- **No nested parallel regions in v1.** A region body declaring its own `:type :parallel` throws `:rf.error/machine-parallel-nested-not-supported` (`machines.cljc:1758`).
- **Broadcast is to every region.** One event-keyword goes to every region's `:on` table; regions whose tables don't list that event are no-ops for that dispatch. Don't broadcast a region-private event with a generic name — namespace it (`:form/submit-valid`, not `:submit-valid`) to make the per-region intent visible.
- **`:after` and `:invoke` are scoped per region.** Each region carries its own `:after`-epoch counter at `[:data :rf/after-epoch-by-region <region-name>]` — a sibling region's transition does NOT invalidate this region's in-flight `:after` timers (`machines.cljc:362`, Spec 005 §Per-region `:always` / `:after` / `:invoke` scoping).

## When to reach for parallel regions vs N machines

Per Spec 005 §When to reach for parallel regions: pick parallel regions when the axes (a) share a domain (the same conceptual feature — data + form + mode for a todos screen), (b) need a unified `:data` map, and (c) want a unified tag-set for view queries. Pick N separate `rf/reg-machine` calls when the axes are unrelated lifecycles (a websocket connection + a separate authentication flow + a separate route) that happen to coexist in the app.

## Deeper material

For the full parallel-regions contract — broadcast routing, per-region scoping, capability gating, registration-time validation — see `SKILL-REDIRECT.md` → *EP — State machines (005)* §Parallel regions.
