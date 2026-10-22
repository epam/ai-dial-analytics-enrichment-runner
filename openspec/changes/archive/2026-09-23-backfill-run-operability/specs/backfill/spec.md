## MODIFIED Requirements

### Requirement: Full-replay backfill on an independent cursor

The runner SHALL run a backfill that re-drives a pipeline's history on its own cursor and progress in
`run_history`, independent of the live `pipeline_cursor`. A backfill MAY be bounded to a `[from, to]` version
window, which SHALL be passed to ADAS on every request and SHALL bound the keyset rather than replace the
cursor — so a bounded run still advances, resumes and stalls exactly as an unbounded one does. An unbounded
backfill SHALL behave as before, replaying from the source start.

The backfill SHALL drive whichever read its pipeline's kind requires, matching the live path's routing:
- a **row-grain Class-2** pipeline's backfill scans pages and enqueues chunks;
- a **group** pipeline's backfill re-drives the dirty-mark over the window and stops there — the ordinary
  readiness sweep decides when a marked group is evaluated, so the pipeline's `idle`, `max_staleness` and
  `cost_ceiling` still apply and backfill SHALL NOT enqueue group work items itself;
- a **Class-1** pipeline's backfill drives the materialize loop bounded by the window, advancing the run cursor
  only on a successful response.

A backfill SHALL drain batch by batch, advancing its cursor and row count each batch, and SHALL mark the run
done when no more pages remain. It SHALL honour the same enqueue guards as the live path: it SHALL record page
dead-letter rates to the DLQ circuit breaker and SHALL pause the run when its pipeline is paused (by an operator or
the breaker) or over its daily spend budget; a page that reports `has_more` but does not advance the cursor
SHALL pause the run for investigation rather than looping, and SHALL leave the run's stored cursor as it was
rather than clearing it — a cleared cursor would restart the run from the beginning of its window on resume.

A run SHALL report the rows it has **processed** and SHALL NOT report a total, a remaining count or a
completion fraction, because none of them is knowable. ADAS exposes no cardinality on any endpoint the runner
calls and `has_more` says only that a page filled to its limit; the cursor is opaque and ADAS-owned, so a
position cannot be turned into a fraction even within a bounded window; the window's upper bound moves with
ADAS's safety-lag clamp, and an unbounded run has no upper bound at all; and a source count would not be
comparable with the processed count anyway, since sampling, the pipeline's server-side filter, skipped rows
and — for a group pipeline — the difference between member rows and groups all separate the two. For a
Class-1 run the count accumulates rows ADAS **wrote**, not rows read, which is a different population again.
A total is therefore not a missing implementation but a quantity this service cannot obtain without a
declaration-aware count from ADAS; acquiring one is a paired change in both repos, not a field to be filled
in. Elapsed time and a rate computed across two reads are the honest progress signals, and they need nothing
stored.

For a group pipeline, completing the run SHALL mean the window has been **marked**, not that its groups have been
evaluated — the evaluation follows on the sweep's schedule and under its gates.

#### Scenario: A backfill drains history and completes

- **GIVEN** a started backfill for a pipeline and a single available scan page (no more pages)
- **WHEN** the backfill batch runs
- **THEN** the page's chunks are enqueued, the run's cursor and row count advance, and the run is
  marked done

#### Scenario: A backfill pauses when its pipeline is paused

- **GIVEN** a running backfill whose pipeline becomes paused (e.g. the DLQ circuit breaker trips)
- **WHEN** the next batch runs
- **THEN** the run is paused and no further pages are drained

#### Scenario: A bounded backfill touches only its window

- **GIVEN** a backfill started with a `[from, to]` window
- **WHEN** its batches run
- **THEN** every request to ADAS carries that window

#### Scenario: A group pipeline's backfill marks rather than enqueues

- **GIVEN** a started backfill for a group pipeline
- **WHEN** its batch runs
- **THEN** the window's groups are marked dirty and no group work item is enqueued by the backfill itself

#### Scenario: A Class-1 pipeline's backfill materializes its window

- **GIVEN** a started backfill for a Class-1 pipeline
- **WHEN** its batch runs
- **THEN** it drives the materialize loop with the window, enqueues nothing, and advances the run cursor only
  on a successful response

#### Scenario: A run reports rows processed and no total

- **GIVEN** a backfill run at any point in its life, bounded or unbounded
- **WHEN** the run is inspected
- **THEN** it reports the rows it has processed and reports no total, no remaining count and no completion
  fraction

### Requirement: Backfill runs are pausable, resumable and cancellable

The runner SHALL let an operator start, inspect, pause, resume, and cancel a backfill run, optionally bounding
a run to a version window at start. A run that is paused or cancelled SHALL stop draining; a paused run SHALL
resume from its committed cursor when resumed, retaining its window.

Every lifecycle verb SHALL be guarded by the run's current state, and a verb applied to a state it does not
admit SHALL return `409` without changing the run:

- **pause** SHALL admit only a `running` run;
- **resume** SHALL admit only a `paused` run — resuming a done, cancelled, or already-running run SHALL
  return `409`;
- **cancel** SHALL admit a `running` or a `paused` run. Cancel keeps the wider domain deliberately:
  abandoning a paused run is the ordinary way a run ends.

A run's completion instant SHALL be written once. Cancelling a run that has already finished or already been
cancelled SHALL be refused rather than rewriting `finished_at`, because `run_history` is the only record of
what a backfill covered and a rewritten completion instant cannot be recovered.

Guarding one verb of the three is not a guard. An unguarded `pause` writes `paused` over a `done` run while
its `finished_at` stays where it was — a row no state machine admits — and `resume` then re-arms that run
from its end cursor, draining whatever the source holds past it at full model cost and re-blocking the
pipeline's `running` conflict. The refusal SHALL be server-side for the same reason: the ops verbs are driven
from `curl` as well as from a client, and a client's own state machine cannot close the race between a polled
listing and the run that finished while it was on screen.

**At most one batch SHALL advance a run, and only while it is running.** Each page's commit SHALL advance the
run only if the run is still `running`, its stored cursor is still the one the page was read from, and its
pinned pipeline generation is still the one the batch drains under; when any has changed, the page's commit — its enqueued work and its dead letters included — SHALL be rolled
back and the batch SHALL stop without chaining a successor or recording a failure. This is what makes every
other rule here hold under concurrency. A batch reads the run once and then drains many pages; an operator
who cancels meanwhile has taken the later decision, and without the check the batch would keep enqueuing
paid work after the cancel. A resume, a retried batch and a batch revived after a crash can each be alive
for the same run at once, and without the check two of them would drain it twice. With it, extra executions
cost at most one wasted source read each.

The runner's **own** transitions — pausing at a gate, marking a run done — SHALL likewise take effect only on a
run that is still `running` (a gate's pause, only on one still pinned to the generation the batch read), and
SHALL otherwise leave the run as it finds it. Without the generation condition, a batch that outlived a
pause, a declaration edit and a resume would pause the freshly resumed run with `pipeline_changed` the moment
it compared the new generation to the one it read. A batch that wrote `done` or
`paused` over an operator's cancel would rewrite the completion instant this requirement says is written
once. No transition SHALL be written by reloading and saving the whole run, since that writes back the
cursor and counters it loaded and can move a run's cursor backwards.

**Resume SHALL leave exactly one live chain.** It SHALL schedule the next batch under an instance that no
earlier execution of the run can already hold — a batch still executing when the resume lands would
otherwise swallow the schedule and then exit on the `paused` it read, leaving the run `running` with no
chain. Any older execution that later fires is stopped by the commit check above.

A backfill SHALL NOT be started or resumed into a condition that would pause it on its first batch. With the
pipeline cache populated: a pipeline absent from the cache SHALL return `404 not_found`; a pipeline that is
paused, or over its daily spend budget, SHALL return `409 backfill_conflict` naming the condition; and a
pipeline that already has a `running` run SHALL return `409` naming that run's id, so the operator can
inspect the thing blocking them without a second query. The database SHALL enforce at most one `running`
run per pipeline, so two concurrent starts, or a start racing a resume, cannot both succeed; the loser of
such a race SHALL also receive `409`, whose message SHALL say how to find the running run (its id is not
readable inside the aborted transaction). Only that constraint's violation SHALL be reported as a conflict;
any other integrity violation is a defect and SHALL NOT be. While the cache
has not yet been populated since the process started, start and resume SHALL return `503` with a stable
code, because an empty cache is the runner's own ignorance and a `404` would tell the operator their
pipeline does not exist. A cursor stall cannot be checked before a batch runs, and resuming a run paused for
one is not refused.

A window whose `from` is not strictly before its `to` SHALL be rejected with `400` at the runner's own API,
rather than creating a run that could only fail on its first batch. Inspecting an unknown run id SHALL return
`404 not_found`.

A run's window SHALL be readable from the run itself, so an operator can tell what a past run covered without
reconstructing the request that started it.

A backfill SHALL be started at `POST /v1/pipelines/{name}/backfill`, and the run it creates SHALL report the
pipeline it belongs to under the JSON property `pipeline_name`. The run-scoped paths (`/v1/backfills/{runId}`
and its pause, resume and cancel verbs) are addressed by run id and are unaffected, which is precisely why the
property on the run body is the one place the identity surfaces there.

#### Scenario: A paused run does not drain

- **GIVEN** a started backfill that is then paused
- **WHEN** its batch task runs
- **THEN** nothing is scanned or enqueued and the run stays paused

#### Scenario: Resuming a non-paused run is rejected

- **GIVEN** a backfill run that is done, cancelled, or running
- **WHEN** an operator resumes it
- **THEN** the response is `409` and the run's state is unchanged

#### Scenario: Pausing a run that is not running is rejected

- **GIVEN** a backfill run that is done, cancelled, or already paused
- **WHEN** an operator pauses it
- **THEN** the response is `409`, the run's state is unchanged, and its `finished_at` is unchanged

#### Scenario: A finished run's completion instant is written once

- **GIVEN** a backfill run that is done, or that has already been cancelled
- **WHEN** an operator cancels it
- **THEN** the response is `409` and the recorded completion instant is not rewritten

#### Scenario: A paused run may still be cancelled

- **GIVEN** a paused backfill run
- **WHEN** an operator cancels it
- **THEN** the run is cancelled and its completion instant is recorded

#### Scenario: A cancel stops a draining batch at its next page

- **GIVEN** a running backfill whose batch is mid-drain
- **WHEN** an operator cancels the run
- **THEN** the batch's next page commit enqueues nothing and is rolled back, the batch chains no successor,
  the run stays cancelled with the completion instant the cancel recorded, and no batch failure is recorded

#### Scenario: A batch does not overwrite an operator's decision

- **GIVEN** a running backfill whose batch is mid-drain
- **WHEN** an operator cancels the run and the batch then reaches the end of the window, or a pause gate
- **THEN** the run stays cancelled and its completion instant is the cancel's

#### Scenario: Pause then resume during a draining batch yields one drain

- **GIVEN** a running backfill whose batch is mid-drain
- **WHEN** an operator pauses the run and resumes it before the batch has finished
- **THEN** exactly one execution advances the run from then on, and its processed-row count counts each page
  once

#### Scenario: Resume while a retry is pending or executing leaves a live chain

- **GIVEN** a paused run whose failed batch has a retry pending, or is executing at the moment of the resume
- **WHEN** an operator resumes it
- **THEN** the run drains from its committed cursor promptly, by exactly one execution, and is never left
  `running` with no scheduled batch

#### Scenario: A second concurrent backfill for a pipeline is rejected

- **GIVEN** a pipeline that already has a running backfill
- **WHEN** an operator starts another backfill for the same pipeline, or resumes a paused one of its runs
- **THEN** the response is `409`, its message names the running run's id, and no second run is running

#### Scenario: A backfill is not started or resumed into a gate

- **GIVEN** a pipeline that is paused, or is over its daily spend budget
- **WHEN** an operator starts a backfill for it or resumes one of its paused runs
- **THEN** the response is `409` naming the condition, and no run is created or set running

#### Scenario: Start and resume before the first sync are unavailable, not not-found

- **GIVEN** a process whose pipeline cache has not yet been populated
- **WHEN** an operator starts or resumes a backfill
- **THEN** the response is `503` with a stable code, not `404`

#### Scenario: A run reports the pipeline it belongs to

- **GIVEN** a backfill started for a pipeline
- **WHEN** the run is inspected at `GET /v1/backfills/{runId}`
- **THEN** the response carries that pipeline's name under `pipeline_name`

#### Scenario: Unknown run id is not found

- **WHEN** an operator requests a backfill run id that does not exist
- **THEN** the response is `404 not_found`

#### Scenario: An inverted window is rejected

- **WHEN** an operator starts a backfill whose `from` is not strictly before its `to`
- **THEN** the response is `400` in the standard error shape and no run is created

#### Scenario: A run reports the window it covers

- **GIVEN** a backfill started with a window
- **WHEN** an operator inspects the run
- **THEN** the run reports that window

## ADDED Requirements

### Requirement: A paused backfill run says why, and since when

A paused backfill run SHALL record the condition that paused it and the instant the pause began, and SHALL
report both on the run. The reason SHALL be drawn from a closed set, constrained by the database, covering
every site that pauses a run: an operator's request, the pipeline being paused by an operator, the pipeline
being paused by the DLQ circuit breaker, the daily spend budget being exhausted, a cursor that did not
advance, the pipeline being absent from the cache, and the pipeline's declaration having changed since the
run was started.

One word is not an answer. The conditions call for materially different operator responses. The condition
behind a spend pause lifts at 00:00 UTC; the condition behind a breaker pause lifts when the breaker's window
elapses, if breaker expiry is enabled; an operator's pipeline pause lifts when a human decides; **a cursor
stall lifts only when somebody fixes something** — resuming it without investigating re-enters the same
non-advancing page and re-pauses, which is why the guard exists at all. In every case it is the *condition*
that lifts: the run itself stays paused until an operator resumes it. The runner computes the distinction at
the moment it pauses and, before this requirement, wrote it only to a log line.

The reason SHALL record what **paused** the run, not what currently holds it. After a restart clears the
in-memory pipeline-pause registry, a run paused for a pipeline pause still reports that reason; the resume
checks in "Backfill runs are pausable, resumable and cancellable" are what answer whether it can run now.

A pipeline pause's origin SHALL be recorded as its own reason value rather than collapsed into a single
"pipeline paused" value. The runner holds the origin at pause time, and the alternative — re-deriving it from
the paused-pipeline listing — fails in precisely the case where the question is hardest, because that listing
is in-memory and a restart clears it while the run's pause survives.

The reason and the instant SHALL be present **only while the run is paused**, and always together. Resuming
a run SHALL clear both in the same statement that sets it running, and cancelling a paused run SHALL clear
both in the statement that cancels it. A stale reason on a running run is worse than no reason, because it
reads as current; on a cancelled run it would answer "why is this paused?" for a run that is not.

The log lines SHALL remain, naming the actual origin of a pipeline pause. They are the record of the event;
the stored reason is the record of the condition, and `runner-ops` already requires those to be reported
differently.

#### Scenario: A paused run reports which condition paused it

- **GIVEN** a backfill run paused because its pipeline's daily spend budget is exhausted
- **WHEN** the run is inspected
- **THEN** it reports a reason naming the spend budget, and the instant the pause began

#### Scenario: A pipeline pause's origin survives on the run

- **GIVEN** one run paused because an operator paused its pipeline and one paused because the circuit breaker
  did
- **WHEN** each run is inspected, including after a restart
- **THEN** each reports a different reason, and neither depends on the in-memory paused-pipeline listing
  still holding an entry

#### Scenario: A cursor stall is distinguishable from an ordinary pause

- **GIVEN** a run paused because ADAS reported more rows without advancing the cursor
- **WHEN** the run is inspected
- **THEN** its reason identifies the stall, so a caller can tell an operator to investigate rather than
  resume

#### Scenario: A lifted condition does not resume the run

- **GIVEN** a run paused because its pipeline's spend budget was exhausted
- **WHEN** the UTC day rolls over
- **THEN** the run stays paused, with its reason, until an operator resumes it

#### Scenario: Resuming clears the reason

- **GIVEN** a paused run reporting a reason and a pause instant
- **WHEN** an operator resumes it
- **THEN** the run reports neither, and reports running

#### Scenario: Cancelling a paused run clears the reason

- **GIVEN** a paused run reporting a reason and a pause instant
- **WHEN** an operator cancels it
- **THEN** the run reports cancelled and reports neither the reason nor the pause instant

#### Scenario: A run that is not paused reports no pause reason

- **GIVEN** a running, done or cancelled backfill run
- **WHEN** the run is inspected
- **THEN** neither the reason nor the pause instant appears in the response

#### Scenario: An unrecognised reason is refused by the database

- **WHEN** a write attempts to store a pause reason outside the permitted set
- **THEN** the database rejects it

### Requirement: A backfill whose pipeline leaves the cache pauses rather than wedging

When a backfill batch finds its pipeline absent from a **populated** pipeline cache, the runner SHALL pause
the run with the absent-pipeline reason rather than returning without changing state. A pipeline is absent
when it is disabled or deleted in ADAS, and also when the runner refuses it as unexecutable, since a refused
pipeline is kept out of the cache; the log line and the reason's documentation SHALL say both. While the
pipeline remains absent, resuming that run SHALL return `404 not_found`, matching the runner's answer to
starting a backfill for a pipeline absent from the cache.

A batch that fires before this process has populated the cache even once SHALL be **held** — rescheduled
after a short fixed delay without recording a failure, exactly as the work-item lanes hold one — rather than
paused. An empty cache at startup is the runner's own ignorance, not a fact about the declaration feed, and
without this every backfill whose batch is due across a restart would pause itself with a reason that is
false.

Returning without a state change wedged the run. The backfill batch is a self-chaining one-time task, so an
execution that returns normally is removed and nothing schedules the next link: the chain was dead while the
row still read `running`, which made the run un-resumable by its own state guard and blocked every future
backfill for that pipeline behind a permanent `409`. No error was raised, no counter moved, and the run's row
was indistinguishable from a healthy slow one. It was also reached by the most intuitive way of stopping a
runaway backfill, which is disabling the pipeline in ADAS.

The runner SHALL NOT resume such a run automatically when the pipeline returns. A pipeline's absence is
almost always an operator's decision taken in ADAS for reasons the runner does not hold, and a pipeline that
reappears has at minimum been re-enabled and may carry an edited declaration — whether the remainder of the
window should be re-driven under it is a judgement the runner cannot make. The run keeps its cursor and its
window and waits.

#### Scenario: A batch whose pipeline is gone pauses the run

- **GIVEN** a running backfill whose pipeline is disabled or deleted in ADAS and has left a populated cache
- **WHEN** its next batch runs
- **THEN** the run is paused with the absent-pipeline reason and its committed cursor is unchanged

#### Scenario: A batch that fires before the first sync is held, not paused

- **GIVEN** a running backfill whose batch becomes due after a restart, before the first pipeline sync
- **WHEN** the batch runs
- **THEN** the run stays running with no pause reason and no recorded failure, and the batch runs again once
  the cache is populated

#### Scenario: A paused run does not block the pipeline's next backfill

- **GIVEN** a run paused because its pipeline left the cache
- **WHEN** an operator starts a new backfill for that pipeline once it is back
- **THEN** the new run is created rather than refused as a conflict

#### Scenario: Resuming into a still-absent pipeline is refused

- **GIVEN** a run paused with the absent-pipeline reason whose pipeline is still not in a populated cache
- **WHEN** an operator resumes it
- **THEN** the response is `404 not_found` and the run stays paused

#### Scenario: A returned pipeline does not restart the run by itself

- **GIVEN** a run paused with the absent-pipeline reason
- **WHEN** the pipeline is re-enabled and the next sync returns it to the cache
- **THEN** the run stays paused until an operator resumes it, and resuming then drains from the committed
  cursor

### Requirement: A backfill whose pipeline declaration changes mid-run pauses

A run SHALL record the pipeline generation it is draining under. When a batch finds the cached pipeline at a
different generation, the runner SHALL pause the run with the declaration-changed reason before reading
another page, rather than continuing under the new declaration.

Continuing silently would switch declarations part-way through the window, and it would also leave a gap.
Work items already enqueued under the old generation are discarded by the worker as stale, while the run's
cursor has already moved past the pages they came from, so no later batch will read those rows again. The
pause does not recover them; it stops the gap from growing and puts the decision in front of an operator.
The operator decides: either resume, which adopts the current generation and continues from the committed
cursor, or cancel and start a new run bounded to the window the old one covered.

#### Scenario: An edit mid-run pauses the run

- **GIVEN** a running backfill started under one pipeline generation
- **WHEN** the pipeline's declaration is edited, the cache syncs the new generation, and the next batch runs
- **THEN** the run is paused with the declaration-changed reason and no page is read under the new generation

#### Scenario: Resuming after an edit adopts the current generation

- **GIVEN** a run paused with the declaration-changed reason
- **WHEN** an operator resumes it
- **THEN** the run records the current generation and drains from its committed cursor under it

### Requirement: A run records why its last batch failed, and does not become terminal

A backfill batch that fails SHALL record on a still-running run a short, classified description of why it
failed, and the instant it failed. The run SHALL report both. A successful page commit SHALL clear them, so a
stale error cannot read as current on a run that is making progress. The description SHALL be classified
rather than copied: the curated text of a failure the runner raises itself (a page missing a column, an
unaccounted page, an incompatible ADAS), the HTTP status and ADAS error code of an upstream rejection, and
only the failure's type otherwise (together with its root cause's type when it wraps one, since a wrapper's
type alone says nothing). It SHALL be length-capped. A raw exception message SHALL NOT be stored or
served, since it can carry an upstream response body or a statement's bound values, and this is the first
surface that would serve one.

A batch failure SHALL be counted per pipeline, and the failed batch SHALL be rescheduled on the worker's
jittered backoff rather than at a fixed interval, so a transient upstream failure recovers in seconds while
a sustained one is probed less and less often. A batch whose failure is a **held** condition — a pipeline
cache not yet synced, an incompatible ADAS — SHALL be held exactly as the work-item lanes hold one, counting
no failure and consuming no attempt, because counting a hold would corrupt the measurement the count exists
to provide. A failure of a run that is no longer running SHALL NOT be recorded or retried; its execution
SHALL be removed. A failure while recording the failure SHALL NOT prevent the reschedule.

A backfill batch SHALL NOT be dead-lettered and its retry SHALL NOT be bounded by an attempt budget. Its
payload is a run id, so there is nothing to archive and nothing to requeue, and stopping the chain without
moving the run's state would leave the run `running` with no chain, un-resumable by its own state guard and
blocking every future backfill for that pipeline.

**A run SHALL NOT enter a terminal failed state.** Most batch failures are transient and the retry is the
correct response; every rule for entering a terminal state is wrong in a different way. Failing on the first
error turns an upstream blip into a dead run that must be restarted from scratch. Failing after a run of
errors destroys the operator's options at the moment they are most wanted — the run's cursor and window are
still good, and what the operator wants is to fix the cause and continue, which `paused` allows and terminal
does not. Failing on a permanent classification would rest on a rule defined for work items, known to be
approximate, and blind to the accounting refusals that are the likeliest permanent cause. Row failures are
not a reason either: a run over a million rows with four hundred dead letters has done what it was asked to,
and a failed state would erase the difference between that and a replay that never ran.

What the run owes an operator is therefore **visibility, not a verdict**: enough on the row to tell a stuck
run from a slow one, with cancel still available and the committed cursor intact. A run cancelled while its
batch was failing SHALL keep its last failure, because "why was this run given up on?" is what it answers.

#### Scenario: A failing batch says what failed

- **GIVEN** a backfill batch that fails because its page does not carry the pipeline's grain column
- **WHEN** the failure is handled
- **THEN** the run reports a classified reason naming the missing column and the instant, stays `running`,
  and is rescheduled

#### Scenario: An upstream rejection is reported without its body

- **GIVEN** a backfill batch that fails because ADAS rejects the request
- **WHEN** the failure is handled
- **THEN** the run's reason carries the HTTP status and ADAS's error code, and none of the response body

#### Scenario: A recovered run stops reporting the failure

- **GIVEN** a run whose last batch failed and whose next batch succeeds
- **WHEN** the successful page commits
- **THEN** the run reports neither a reason nor an instant

#### Scenario: A run that has never failed a batch reports nothing

- **GIVEN** a backfill run whose batches have all succeeded
- **WHEN** the run is inspected
- **THEN** neither property appears in the response

#### Scenario: A permanently failing batch is visible rather than terminal

- **GIVEN** a batch whose failure recurs on every attempt
- **WHEN** it has failed repeatedly
- **THEN** the run is still `running` with its committed cursor and window intact, reports the latest
  reason, is countable on the per-pipeline failure counter, and can be cancelled by an operator

#### Scenario: A cancelled run keeps its last failure

- **GIVEN** a run whose batch is failing
- **WHEN** an operator cancels it
- **THEN** the run reports cancelled together with its last failure reason and instant, and its failed batch
  is not retried again

#### Scenario: A held batch is not a failure

- **GIVEN** a backfill batch that meets an incompatible ADAS or a pipeline cache that has not yet synced
- **WHEN** the batch is handled
- **THEN** it is held and rescheduled, the run records no reason, and the failure counter does not move

#### Scenario: Row dead letters do not fail the run

- **GIVEN** a backfill run whose pages produce dead letters for individual rows
- **WHEN** the run reaches the end of its window
- **THEN** the run is marked done rather than failed, and the dead letters remain the record of what could
  not be enriched

### Requirement: Backfill runs are listable

The runner SHALL expose a listing of backfill runs at `GET /v1/backfills`, narrowable by pipeline name, by run
state and by pause reason, ordered newest first by start time, and bounded by a clamped `limit` with no
paging. The response SHALL be an envelope carrying the same run representation the single-run read returns,
so one model serves a row and a detail view alike. State and pause-reason values SHALL be accepted in the
lowercase spelling the run body reports, case-insensitively.

Without it a run is reachable only by an id handed out once, in the `202` body of the request that created
it. Losing that response — a closed terminal, a reloaded page, a shift handover, a restarted pod — makes the
run unreachable through the API entirely, and the only recovery is a `SELECT` against `run_history`. That
leaves four operator questions with no API answer: which run is holding the `running` conflict that just
refused a start; whether any run is stuck; what a past run's window covered, which is the question the
window columns were added for; and which paused runs have accumulated, given that a paused run deliberately
does not block a new one and any number of them may pile up, each holding a cursor. The pause-reason filter
answers the triage question directly: every run that stalled on its cursor, everywhere.

An unrecognised state or reason value SHALL be refused with `400` rather than answered with an empty list.
An empty list reads as "no runs in that state", which is the wrong answer to a typo and the dangerous one,
since the question is usually "is anything running right now".

The listing SHALL use the same parameter spellings and the same clamp shape as the dead-letter listing — an
optional `pipeline_name`, a `limit` clamped to a constant ceiling, no cursor, no total and no paging — so an
operator who has learned one listing has learned the other.

#### Scenario: Every run is findable without its id

- **GIVEN** backfill runs started earlier whose `202` responses are no longer held
- **WHEN** the runs are listed
- **THEN** each is returned with its id, pipeline, state and start time, newest first

#### Scenario: The listing narrows by pipeline, state and pause reason

- **GIVEN** runs in several states and pause reasons across more than one pipeline
- **WHEN** the listing is requested with any combination of a pipeline name, a state and a pause reason
- **THEN** only the matching runs are returned

#### Scenario: Paused runs across every pipeline are enumerable

- **GIVEN** more than one pipeline carrying an abandoned paused run
- **WHEN** the listing is requested filtered to the paused state and no pipeline
- **THEN** every paused run is returned, whichever pipeline it belongs to

#### Scenario: The value the run reports is the value the filter accepts

- **GIVEN** a run whose body reports `"state": "running"`
- **WHEN** the listing is requested with `state=running`, or with `state=RUNNING`
- **THEN** the run is returned in both cases

#### Scenario: A misspelled filter value is refused

- **WHEN** the listing is requested with a state or pause-reason value outside the permitted set
- **THEN** the response is `400` in the standard error shape rather than an empty list

#### Scenario: The limit is clamped

- **WHEN** the listing is requested with a limit above the endpoint's ceiling
- **THEN** at most the ceiling's worth of runs is returned

#### Scenario: A listed run carries the same shape as a fetched one

- **GIVEN** a run that is both listed and fetched by id
- **WHEN** the two responses are compared
- **THEN** the run is represented identically in each
