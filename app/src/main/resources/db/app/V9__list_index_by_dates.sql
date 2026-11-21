-- The index of the list and the values of its works are written of what changed since their last writing, by the dates
-- the databases keep (worklist.ListIndexing, worklist.WorkValues): the items they named in the queue dropped
DELETE FROM queued_key
WHERE task_key LIKE 'writeListIndex' || CHAR(9) || '%'
   OR task_key LIKE 'writeWorkValues' || CHAR(9) || '%';
