# Grade comparison investigation

## Evidence

- The [official BilimClass mobile guide](https://help.bilimland.kz/l_rus/knowledge_base/item/356645) describes lesson grades, subject history and predicted grades. It does not document grade comparison, its data source or its calculation.
- MaterialClass currently opens a placeholder from `GradeTile` in `app/src/main/java/org/materialclass/ui/GradesScreens.kt`. It states that classmates' grades are unavailable and makes no comparison request.
- The existing grade pipeline combines the subjects response with the current student's journal records by schedule UUID. The journal request is scoped to the current user and student groups. It does not establish an API contract for classmates' grades or aggregate comparison statistics.

## Unknowns and next steps

The upstream application's comparison UI, exact semantics, request endpoint, authorization and response shape have not been verified. In particular, the term could mean comparison with classmates or another kind of comparison. Do not infer an endpoint, expose other students' data, or fabricate statistics from the current student's grades. Inspect an authorized upstream app session and collect only sanitized request and response schema evidence before implementing this feature. A successful observation for one account does not establish access for other roles or schools.
