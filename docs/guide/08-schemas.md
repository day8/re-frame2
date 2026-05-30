# 08 - Schemas

You like to sleep at night, and you have noticed that most bugs are just malformed data with better public relations. This chapter teaches where schemas sit in re-frame2: at the boundaries where events, effects, subscriptions, and `app-db` slices can be checked before bad shape becomes bad behaviour.

re-frame2 uses Malli schemas. You declare the shape near the thing that owns it.

```clojure
(def CartLine
  [:map
   [:sku :string]
   [:qty pos-int?]])

(rf/reg-app-schema [:cart :items]
  [:map-of :string CartLine])
```

Now the cart slice has a public contract. Tools can display it, tests can depend on it, and invalid updates can be surfaced as structured schema failures instead of mysterious UI sadness.

## Validate at boundaries

Useful boundaries include:

| Boundary | What gets checked |
|---|---|
| Event args | The payload entering the cascade. |
| app-db path | The state after a handler commits. |
| Subscription return | The value a view will read. |
| Effect args | The request handed to an effect handler. |
| Cofx values | Inputs injected into handlers. |

Do not validate everything in every hot path just because you can. Validate where a bad value crosses ownership. That is where the error message still has useful context.

## Schemas are also documentation

A schema is not merely a bouncer. It is a compact explanation of the feature's data model.

```clojure
(def RemoteData
  [:map
   [:status [:enum :idle :loading :success :failure]]
   [:data {:optional true} :any]
   [:error {:optional true} :any]])
```

That says more than a paragraph of prose and has the courtesy to fail when the code lies.

## Privacy and size marks

Schemas can also declare `:sensitive?` and `:large?` metadata. That information feeds elision: traces, share URLs, Story snapshots, and off-box tool surfaces can replace secrets with `:rf/redacted` and giant blobs with summaries.

Validation and elision are different jobs, but schema is the right declaration site for both because both describe the data's public contract.

## Pitfall: schema as theatre

A schema nobody reads, nobody runs, and nobody attaches to a boundary is decorative YAML with parentheses. Put schemas where they change runtime behaviour or test failure quality. Otherwise you are just making your future self maintain a second copy of a wish.
