# The application: `app`

`app.mv.db` — The application's own database, one of it: its settings and the settings and job states of every source,
and the queue of its computations with their last runs.

| Table | What it holds |
|---|---|
| `setting` | a setting by its name ("encoder.text", "source.<id>.…", "job.<id>.running"), its value as text |
| `queued_work` | a work of the queue not done yet — planned, waiting its turn or running — from its plan to its end: what it is, its plan's number, when it is due, since when, why, whether the user asked; what a stop or a crash left is planned again at the next start |
| `work_run` | the last runs of every kind of work: when each began and ended, why, whether it failed or was given up — what a work to come is expected to take |

The diagram is made of the migrations by `SchemaDocsTest`: change it with them.

```mermaid
erDiagram
    queued_work {
        VARCHAR work_key PK
        VARCHAR what
        VARCHAR type_id "null"
        VARCHAR source_id "null"
        VARCHAR facet "null"
        VARCHAR item "null"
        BIGINT plan_no
        TIMESTAMPTZ due
        TIMESTAMPTZ since
        VARCHAR reasons
        BOOLEAN asked
    }
    setting {
        VARCHAR name PK
        CLOB content "null"
    }
    work_run {
        BIGINT id PK
        VARCHAR kind_key
        VARCHAR what
        VARCHAR type_id "null"
        VARCHAR source_id "null"
        VARCHAR facet "null"
        VARCHAR item "null"
        TIMESTAMPTZ began
        TIMESTAMPTZ ended
        VARCHAR reasons
        BOOLEAN failed
        BOOLEAN given_up
    }
```
