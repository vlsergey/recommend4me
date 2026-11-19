-- The vectors of the sets were made by a loop of the background going over every set every ten minutes; they are made
-- of the events that grow a set now, in the queue (SetVectorMaking). What the loop had not made yet is made once: every
-- source it went over, of its runs kept, left in the queue as a work of the source, which the start plans again as every
-- work of the source waiting.
MERGE INTO queued_work (work_key, what, source_id, plan_no, due, since, reasons, asked)
KEY (work_key)
SELECT DISTINCT CONCAT('setVectors', CHAR(9), CHAR(9), source_id, CHAR(9), CHAR(9)), 'setVectors', source_id, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'setsGrew=1', FALSE
FROM work_run WHERE what = 'setVectors' AND source_id IS NOT NULL;
