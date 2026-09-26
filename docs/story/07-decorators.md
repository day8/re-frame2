# Decorators

A decorator wraps or prepares a variant: a theme provider around the view, a
signed-in session in its frame, a stub in place of an effect. A story or
variant lists the decorators it wants under `:decorators`, each as a vector of
the decorator's id and any arguments. A variant's decorators sit inside its
story's.

Register one with `reg-decorator`, in one of three kinds:

```clojure
;; :hiccup wraps the rendered view. :wrap gets the body and the effective args.
(rf.story/reg-decorator :app/card-frame
  {:kind :hiccup
   :wrap (fn [body args] [:div.card {:class (name (:theme args :light))} body])})

;; :frame-setup prepares the variant's frame before it renders.
(rf.story/reg-decorator :app/signed-in
  {:kind         :frame-setup
   :init         [[:session/restore {:user "ada"}]]
   :app-db-patch {:feature-flags {:beta true}}
   :teardown     [[:session/clear]]})

;; :fx-override stands a stub in for an effect.
(rf.story/reg-decorator :app/no-analytics
  {:kind     :fx-override
   :fx-id    :analytics/track
   :response nil})
```

```clojure
(rf.story/reg-variant :story.login/signed-in
  {:decorators [[:app/card-frame] [:app/signed-in] [:app/no-analytics]]})
```

`:init` events are dispatched and `:app-db-patch` is merged into app-db before
the view renders; `:teardown` events run when the variant's frame is destroyed.
A `:frame-setup` decorator needs at least one of the three. An `:fx-override`
stub records each call instead of performing the effect, and
`:rf.assert/effect-emitted` still sees the effect as emitted.

## Built-in decorators

Four decorators are built in, each named by a Var so a typo fails to compile:

| Var | What it does |
|---|---|
| `rf.story/force-fx-stub-id` | Stubs one effect for this reference: `[rf.story/force-fx-stub-id :rf.http/managed {}]`. |
| `rf.story/layout-debug-measure-id` | Overlays element sizes and spacing. |
| `rf.story/layout-debug-outline-id` | Outlines every element in its own colour. |
| `rf.story/layout-debug-pseudo-id` | Forces pseudo-states, `#{:hover}` by default, or any of `:hover`, `:focus`, `:active` and `:visited`: `[rf.story/layout-debug-pseudo-id #{:focus}]`. |

The Layout-debug panel in the right rail switches the three layout-debug
overlays on and off for the selected variant, without touching its source.

## A decorator for every story

Storybook keeps project-wide wrappers such as a theme provider in `preview.ts`.
In Story it is one registration, made once in your stories namespace:

```clojure
(rf.story/reg-global-decorator :app/theme
  {:kind :hiccup
   :wrap (fn [body _args] [:div.app-theme body])})
```

Every variant now renders inside `:app/theme`. Globals are the outermost layer,
so the stack reads global, then story, then variant, with the earliest-registered
global outermost. Unlike `preview.ts`, the chain is data:
`rf.story/variant-plan` carries the resolved stack under `[:world :decorators]`,
and Docs mode's Decorators table lists the global first. Neither
`rf.story/explain` nor the Explain panel lists the stack, so check the plan or
Docs mode when you want to know what wraps a variant. The
[registration reference](api/registration.md#reg-global-decorator) covers
`clear-global-decorator` and the `configure!` form.

A variant that `:extends` another takes its parent's decorators unless it
declares its own, which replace them ([Composition](07-composition.md#extends)).
