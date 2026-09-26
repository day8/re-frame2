# Composition

Variants often share a starting point: the same submitted form, the same
signed-in user, the same expectation that no warnings fire. Story shares that
context in two ways: `:extends` builds one variant on another, and `:compose`
pulls in registered fragments and checks. Neither hides behaviour, and
`explain` shows where every piece came from.

## Extends

`:extends` specializes another variant:

```clojure
(rf.story/reg-variant :story.login/retrying
  {:extends    :story.login/error
   :script     [[:dispatch [:login/flow [:login/retry {:email    "ada@example.com"
                                                       :password "correct-horse"}]]]]
   :assertions [[:rf.assert/state-is :login/flow :submitting-retry]]})
```

The child starts from the error state its parent's setup reaches, keeps the
parent's stubbed HTTP effect, and runs only its own script and assertions.

The inheritance rule is: **context flows down, verdict is local.**

| Field | Rule |
|---|---|
| `:setup` | parent then child, appended in order. |
| args | deep-merged; the child's win. |
| `:decorators` | the parent's, unless the child declares its own, which replace them. |
| `:network` and the other world inputs | inherited. |
| `:fx-overrides`, `:interceptor-overrides` | inherited; the child's own values win. |
| `:checks` | inherited. |
| `:script` | child-only. |
| ordinary `:assertions` | child-only. |
| tags | union. |

A child inherits the world its parent set up. It does not run the parent's
script or inherit the parent's verdict, so a change to the parent's
assertions never changes a child's result.

## Fragments and checks

Use a fragment for reusable setup, script or world context:

```clojure
(rf.story/reg-fragment :fragment.login/submitted-wrong-password
  {:setup [[:login/flow [:login/submit {:email    "ada@example.com"
                                        :password "wrong"}]]]})
```

Use a check for reusable expectations
([chapter 4](04-the-variant-is-a-test.md#checks-and-assertions)):

```clojure
(rf.story/reg-check :check/no-runtime-warnings
  {:assertions [[:rf.assert/no-warnings]]})
```

Compose them explicitly:

```clojure
(rf.story/reg-variant :story.login/rejected
  {:compose    [:fragment.login/submitted-wrong-password
                :check/no-runtime-warnings]
   :decorators [[rf.story/force-fx-stub-id :rf.http/managed {}]]
   :script     [[:dispatch [:login/flow [:login/failure {}]]]]
   :assertions [[:rf.assert/state-is :login/flow :error]]})
```

A fragment's setup and script come before the variant's own, in the order
`:compose` lists them. A fragment's args are deep-merged in, and a check's
assertions run with the variant's.

Fragments are flat. A fragment does not compose another fragment: a fragment
body carrying `:compose` or `:extends` throws `:rf.error/fragment-shape`. That
keeps the order of setup easy to read and rules out cycles.

## Conflicts

`:fx-overrides` and `:interceptor-overrides` are strict. A value the variant
sets itself always wins. When two composed fragments set different values for
the same effect or interceptor and the variant sets none, the variant cannot
compile: `explain` throws `:rf.error/story-compose-conflict` and a run errors
with it, naming the field and the key. The variant resolves it by stating the
value it wants.

## Reading the result

When composition is involved, use `rf.story/explain` or the Explain panel
([chapter 4](04-the-variant-is-a-test.md#explain)). It shows the source chain,
merge decisions, setup order, script order, checks, assertion locations,
runner requirements and source coordinates.
