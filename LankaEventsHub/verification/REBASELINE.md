# Why `surface.json` was re-baselined

`surface.py` compares the templates' *functional surface* — form actions and
fields, URL expressions, authorization rules, fragment includes, script hooks
and model references — against a saved snapshot. Its job is to prove that a
visual change stayed visual.

The Verdant Events redesign changed two fragment **signatures** and renamed one
loop variable. Those are not behaviour changes, but a signature is part of the
recorded surface, so they showed up as removals and the check stayed red on
every subsequent run — which is how a useful check becomes one nobody reads.

The snapshot was therefore re-saved once the redesign was complete and verified.
The previous snapshot is kept beside it as `surface.pre-redesign.json`, so the
comparison can still be made by hand.

## The eight entries that were re-baselined, and why each is not a regression

| Reported as lost | What replaced it | Why it is not a behaviour change |
|---|---|---|
| `error.html`, `event-detail.html`, `event-list.html`, `home.html`: fragment `~{fragments/navbar :: nav}` | `~{fragments/navbar :: nav(active, solid)}` | Same fragment. It now takes the active section and whether the bar starts solid, so the active state is decided on the server instead of guessed by a script reading `location.pathname`. Every link the old fragment rendered, it still renders. |
| `home.html`: fragment `~{fragments/head :: head('Home')}` | `~{fragments/head :: head('Event Management & Discovery')}` | Same fragment, different page title string. |
| `home.html`: url `@{/events(category=${cat})}` | `@{/events(category=${f.name})}` | Same route, same query parameter, still one link per real category. The loop variable is named `f` because the loop now walks `FeaturedCategory` records (name plus count) rather than bare strings. |
| `home.html`: model `cat`, model `iter` | model `f`, model `i` | Renamed loop variables from the same `th:each`. |

## Second re-baseline: the registration review step, and the groupSize fix

Two further, later changes replaced surface entries rather than only adding
to them - both real functional changes, made and documented (in
`FINAL_AUDIT_REPORT.md`) well before this re-baseline, not changes made to
silence this check.

| Reported as lost | What replaced it | Why it is not a regression |
|---|---|---|
| `event-detail.html`: url and form action `@{/events/{id}/register(id=${event.id})}` | url and form action `@{/events/{id}/register/review(id=${event.id})}`, plus a new `registration-review.html` page whose own form still posts to the original `/events/{id}/register` | "Register now" used to post directly; it now `GET`s a review step first (event details, price breakdown, the attendee's own name/email) before the same original endpoint is ever called. The endpoint the surface reports as lost from event-detail.html did not disappear from the project - it moved to being the review page's form action, which is exactly why it also appears in this run's *added* list, not a genuine loss. |
| `payment-form.html`: field/js_id/model `groupSize` | nothing - deliberately removed, not replaced | The GROUP discount used to trust a client-submitted group size, which let a 1-seat booking claim a group rate by typing a bigger number. The fix ties eligibility to `registration.getSeats()` (the number the server already knows and already charges for) instead, so there is nothing left for a field, a model attribute, or a script to hold - removing all three was the fix, not a side effect of one. |

## Re-running the comparison

```bash
python3 verification/surface.py --check          # against the current baseline
diff <(python3 -m json.tool verification/surface.pre-redesign.json) \
     <(python3 -m json.tool verification/surface.json)   # against the original, pre-redesign snapshot
diff <(python3 -m json.tool verification/surface.pre-second-rebaseline.json) \
     <(python3 -m json.tool verification/surface.json)   # against the snapshot just before THIS re-baseline
```
