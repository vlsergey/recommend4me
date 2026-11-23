-- The drafting of every work of a source is no task any more (draft.WorkDrafts makes the drafts where they are read)
DELETE FROM queued_key WHERE task_key LIKE 'draftWorkLayer' || CHAR(9) || '%' OR task_key = 'draftWorkLayer';
DELETE FROM queued_task WHERE what = 'draftWorkLayer';
DELETE FROM task_run WHERE what = 'draftWorkLayer';
