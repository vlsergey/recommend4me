-- The languages and the languages of the descriptions written once as the user's answers by a step of the start
-- (LanguageAnswers, 9e4ab443, its code gone in 5194ced0) — of Archive of Our Own and F95zone the one the site states,
-- of ficbook and Author.Today Russian to every work there was —, never the user's word: taken back (owner, 2026-11-21:
-- "why is the language counted as answered? sounds like a bug"). They are told apart by the moments that step wrote
-- them in — 35 914 languages on 2026-10-15 between 17:27:23 and 17:27:29, 27 739 languages of the descriptions on
-- 2026-10-16 at 09:40:43 —; the user's own answers, of other moments, stay. A site's stated language stays its word;
-- the model of the languages learns of the user's answers alone. Their works' documents of the index of the list
-- written anew: their date set first
UPDATE normalized_work SET changed_at = CURRENT_TIMESTAMP
WHERE id IN (
    SELECT work FROM work_facet_correction
    WHERE (facet = 'languages'
           AND corrected_at >= TIMESTAMP WITH TIME ZONE '2026-10-15 17:27:23+00'
           AND corrected_at < TIMESTAMP WITH TIME ZONE '2026-10-15 17:27:29+00')
       OR (facet = 'description-language'
           AND corrected_at >= TIMESTAMP WITH TIME ZONE '2026-10-16 09:40:43+00'
           AND corrected_at < TIMESTAMP WITH TIME ZONE '2026-10-16 09:40:44+00')
);
DELETE FROM work_facet_correction
WHERE (facet = 'languages'
       AND corrected_at >= TIMESTAMP WITH TIME ZONE '2026-10-15 17:27:23+00'
       AND corrected_at < TIMESTAMP WITH TIME ZONE '2026-10-15 17:27:29+00')
   OR (facet = 'description-language'
       AND corrected_at >= TIMESTAMP WITH TIME ZONE '2026-10-16 09:40:43+00'
       AND corrected_at < TIMESTAMP WITH TIME ZONE '2026-10-16 09:40:44+00');
