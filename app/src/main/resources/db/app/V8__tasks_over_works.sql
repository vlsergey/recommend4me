-- The tasks over the works replace the filling of the rated works' values, the sweep of the scores and the upkeep of
-- the index of the search (owner, 2026-11-19, docs/computations.md, 9 and 10): the tasks of those kinds left in the queue
-- dropped, with what they were to be done of — the index of the list made anew of its new format plans the tasks over
-- every work.
DELETE FROM queued_key
WHERE task_key LIKE 'fillRatedWorkValues' || CHAR(9) || '%'
   OR task_key LIKE 'scoreWorks' || CHAR(9) || '%'
   OR task_key LIKE 'updateSearchIndex' || CHAR(9) || '%';
DELETE FROM queued_task WHERE what IN ('fillRatedWorkValues', 'scoreWorks', 'updateSearchIndex');
