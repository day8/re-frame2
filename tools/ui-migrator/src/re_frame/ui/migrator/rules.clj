(ns re-frame.ui.migrator.rules
  "The MIG-01..35 rule registry: per-rule tier, gating status, and - for the
  D/R tiers - the PREPARED flag text the migrator emits verbatim (bead
  rf2-gria2b: \"D-tier output = flags with the prepared flag text, NEVER
  auto-rewrites\"). The flag text is transcribed from the canonical rule table
  (prep/w1-migrator-rule-table.md); the W2 migration skill teaches from the
  same vocabulary, so these strings are the tool<->skill contract (Open items 5)
  and must not drift silently.

  Tiers:
    :M  mechanical - the migrator rewrites the source.
    :D  guided     - a decision is the human's; emit the flag (+ suggested
                     rewrite text), never mutate the source. Two D rules are
                     :D in the table but explicitly NON-gating rewrite+flag
                     (MIG-28 ui/spread; Ordering 1) - those DO rewrite; the
                     registry marks them :rewrite? true.
    :R  reject     - no compiled/ruled equivalent exists; emit the reject flag.

  :gating? marks the body rules that, on ANY hit, leave the WHOLE view
  unconverted on the compat tier (Ordering 1). Root-, call-site-, ns- and
  dataflow-level rules never gate a view body."
  (:require [clojure.string :as str]))

(def registry
  "Ordered vector of rule descriptors. See ns docstring for field meaning."
  [{:id "MIG-01" :tier :M :gating? false
    :desc "Form-1 / reg-view view -> ui/defview; positional params -> map props; call sites -> [view {..}]"}
   {:id "MIG-02" :tier :M :gating? false
    :desc "@(subscribe [:q]) -> (sub [:q]) (deref-drop)"}
   {:id "MIG-03" :tier :D :gating? true
    :desc "explicit-frame subscribe/dispatch op"
    :note "explicit-frame op: shipped ui/sub is arity-1 (no pin spelling exported yet); scope the subtree with ui/frame-provider {:frame f} or hold the site"}
   {:id "MIG-04" :tier :M :gating? false
    :desc "{:on-click #(dispatch [:ev x])} -> {:on-click [:ev x]} (dispatch-lifting)"}
   {:id "MIG-05" :tier :M :gating? false
    :desc "#(dispatch [:t (-> % .-target .-value)]) -> [:t :rf.ui/value] (placeholder extraction)"}
   {:id "MIG-06" :tier :M :gating? false
    :desc "(fn [e] (.preventDefault e) (dispatch [:save])) -> {:event [:save] :prevent-default true}"}
   {:id "MIG-07" :tier :M :gating? false
    :desc "^{:key k} [item t] -> [item {:key k ..}] (key-meta -> prop)"}
   {:id "MIG-08" :tier :D :gating? true
    :desc "loop-body legality (unkeyed for / sub|lease in loop / capturing handler)"
    :note "new grammar: missing key = build failure; sub in loops = compile error; loop-capturing handler vectors = compile error - extract a keyed child view (per-row instances)"}
   {:id "MIG-09" :tier :M :gating? false
    :desc "[:> Button {..}] / (r/adapt-react-class X) -> foreign head [Button {..}]"}
   {:id "MIG-10" :tier :D :gating? true
    :desc "fn-valued prop at a foreign-component boundary"
    :note "choose a callback form - identity-as-protocol / callback-ref -> (ui/raw-fn f); needs the event object / a payload -> ui/event; imperative work / stable-identity change-callback -> ui/handler; render prop (pure, fires during the foreign render) -> ui/render-fn. Event VECTORS are not a foreign-boundary form"}
   {:id "MIG-11" :tier :M :gating? false
    :desc "camelCase / alias DOM prop spellings -> pinned kebab (:onClick -> :on-click, :className -> :class, :htmlFor -> :for)"}
   {:id "MIG-12" :tier :M :gating? false
    :desc "(doall (for ..)) -> (for ..) (strip Reagent laziness workaround)"}
   {:id "MIG-13" :tier :D :gating? true
    :desc "markup-returning (map ..) in child position"
    :note "markup-returning map + raw lazy seqs are rejected at compile time; use a keyed for"
    :suggest "(for [t ts] [item {:key (:id t) :t t}])"}
   {:id "MIG-14" :tier :M :gating? false
    :desc "plain hiccup pass-through (+ shipped M/D sub-rules)"}
   {:id "MIG-15" :tier :M :gating? false
    :desc "(rdom/render [app] el) -> (ui/mount [ui/frame-root {..} [app {}]] el)"}
   {:id "MIG-16" :tier :D :gating? true
    :desc "Form-2 / with-let local state"
    :note "Form-2 state - decide: product meaning -> app-db (event+sub; human move) vs ephemeral -> local (placement rule 004 Local state)"
    :suggest "(let [[open? set-open! update-open!] (local false)] ..)  ; @open? -> open?, (reset! open? v) -> (set-open! v), (swap! open? f a) -> (update-open! f a)"}
   {:id "MIG-17" :tier :D :gating? true
    :desc "Form-3 / r/create-class lifecycle"
    :note "host/DOM work -> (effect :connect ..); domain work ('mark viewed') -> route/domain event (dataflow); :on-mount deliberately does not exist. :connect cleanup runs at each DISCONNECT (StrictMode replay makes connect/disconnect cycles expected) - the cleanup must be disconnect-idempotent"}
   {:id "MIG-18" :tier :D :gating? true
    :desc "non-conforming :on-* handler fn (mixed / guarded / no-dispatch imperative)"
    :note "legal as a bare fn on a DOM :on-* (narrow bare-fn law) - or split: local work stays a fn, intent becomes a vector on the natural element; a GUARDED dispatch maps to ui/event's nil => no-dispatch filtering. Imperative dispatch inside a bare fn is (ui/dispatch-fn); pure imperative work is ui/handler; work returning a vector to dispatch is ui/event"}
   {:id "MIG-19" :tier :D :gating? true
    :desc "derived state (r/track / r/cursor / reaction)"
    :note "copy the compute fn into (reg-sub :ns/name ..) (dataflow layer), call sites -> (sub [:ns/name a]). r/cursor also has a WRITE side: every (reset! cursor v) needs a registered event ([:a/set-b v]) - the flag enumerates the write sites alongside the read sites"}
   {:id "MIG-20" :tier :R :gating? true
    :desc "ratom as store / reactive side effects / add-watch on a ratom"
    :note "second state model / render-phase side effects - compile errors here by design; restructure as app-db + events/subs (or props). An add-watch fires synchronously mid-swap! before render; an effect runs after commit - the restructure is the causal-event move (or registered fx for external-world sync), never a mechanical effect rewrite. Every one of these was a latent concurrency bug in Reagent"}
   {:id "MIG-21" :tier :R :gating? true
    :desc "dynamic tag head ([(if big? :h1 :h2) ..])"
    :note "dynamic tag heads rejected at compile time; bind attrs + split branches, or re-frame.ui.data (separate artifact) for genuinely data-driven UI"}
   {:id "MIG-22" :tier :R :gating? true
    :desc "third-party Reagent wrapper component (re-com etc.)"
    :note "third-party Reagent wrapper - per-library decision; last movers. Interim: keep the subtree on the compat tier, or embed under (ui/raw (r/as-element [..])) per the boundary contract sec 2 (same root, frame scoping/teardown rules there). Outward direction ui/->react is S6"}
   {:id "MIG-23" :tier :D :gating? false :staged "S5"
    :desc "SSR (reagent.dom.server/render-to-string)"
    :note "the SSR serialisation path (re-frame.ssr/emit-ui-tree + render-static) lands S5; until then keep the frozen re-frame.ssr compat path. Views using refs/effects need client-only/restructure for SSR (06 sec 1 subset)"}
   {:id "MIG-24" :tier :M :gating? false
    :desc "ns requires fixup (add re-frame.ui; drop reagent requires when zero uses remain)"}
   {:id "MIG-25" :tier :R :gating? false
    :desc "render-phase subscribe over a side-effecting sub body"
    :note "subs are pure here; the effectful part moves to leases/events (guide 03). Dataflow-side finding surfaced in the report, never a view rewrite; the view site converts per MIG-02 once the sub body is pure"}
   {:id "MIG-26" :tier :D :gating? false
    :desc "step-1 ambient frame ops in plain unregistered fns"
    :note "ambient subscribe/dispatch in a plain fn raise :rf.error/no-frame-context - grep for them, don't discover them by clicking. Rewrites in preference order: (1) register (reg-view/reg-view*), (2) hoist the op to the nearest registered ancestor and pass values/ops down, (3) explicit {:frame f}"}
   {:id "MIG-27" :tier :D :gating? false
    :desc "fn-valued prop on an internal-view call site"
    :note "C-13a (shipped): a plain fn prop on an INTERNAL defview call site is a legal, opaque, identity-compared value - NOT a compile error, NOT gated, NOT auto-rewritten. Recommend only: forward a data intent where serializability/tool-visibility is wanted (dispatch-only closure -> bare vector; child places it in its own DOM :on-* site), or ui/handler / ui/render-fn where a phase or stable identity is actually required"}
   {:id "MIG-28" :tier :D :gating? false :rewrite? true
    :desc "computed/dynamic DOM props map -> ui/spread (rewrite + flag, non-gating)"
    :note "a spread site forfeits the static manifest row and the controlled-input synchrony door (which needs a provable literal :value/:checked co-present on the element) - shrink the spread to genuinely pass-through props and lift :value/handlers back to literals"}
   {:id "MIG-29" :tier :M :gating? false
    :desc "callback ref {:ref (fn ..)} -> {:ref (ui/raw-fn (fn ..))}"}
   {:id "MIG-30" :tier :D :gating? true
    :desc "runtime-built markup helper call in child position"
    :note "runtime hiccup DATA has no compiled spelling: template-ize the callee into defview branches (then MIG-01 applies); genuinely data-driven markup -> re-frame.ui.data (separate artifact); interim: the whole view stays on the compat tier"}
   {:id "MIG-31" :tier :M :gating? false
    :desc "render-phase (rf/capture-frame) -> (frame) ops-map body form"}
   {:id "MIG-32" :tier :D :gating? true
    :desc "framework-shipped Reagent-tier component head (route-link)"
    :note "framework component with no ruled compiled spelling - hold the view on the compat tier pending the rf2-5yovjt ruling (compiled route-link counterpart; W5/spec-012 rider). A plain [:a {:href ..}] is NOT an equivalent rewrite (012 Link handling)"}
   {:id "MIG-33" :tier :M :gating? false
    :desc "substrate-adapter boot (rf/init! reagent-adapter/adapter) -> (rf/init! ui/adapter)"}
   {:id "MIG-34" :tier :M :gating? false
    :desc "trusted-markup prop :dangerouslySetInnerHTML -> (ui/html expr)"}
   {:id "MIG-35" :tier :R :gating? true
    :desc "Reagent component-introspection / scheduler API"
    :note "component introspection and scheduler pokes have no compiled equivalent - each encodes a Reagent-specific assumption about this renderer. props/children are the props map and positional children the view already receives; force-update dissolves under memo-by-default + committed slots; next-tick/after-render are effect (post-commit, with cleanup). These calls STILL COMPILE after conversion and fail at runtime - the migrator must detect them; the compile-gate does not"}])

(def by-id
  "Rule descriptor keyed by rule id."
  (into {} (map (juxt :id identity)) registry))

(defn tier    [id] (:tier    (by-id id)))
(defn gating? [id] (boolean  (:gating? (by-id id))))
(defn note    [id] (:note    (by-id id)))
(defn suggest [id] (:suggest (by-id id)))
(defn desc    [id] (:desc    (by-id id)))

(defn action-for
  "Default finding action for a rule id, honouring the two non-gating D
  rewrite rules (MIG-28)."
  [id]
  (let [{:keys [tier rewrite?]} (by-id id)]
    (case tier
      :M :rewrite
      :D (if rewrite? :rewrite :flag)
      :R :reject)))
