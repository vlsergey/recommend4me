-- An account of a site a dump gave by its name, merged into the account of the site's own key of that name (merged_by
-- NAME), is that account, its works keyed by the own key already: one account of the site, not two — the account by
-- the name, of no work any more, goes (owner, 2026-11-21: "consider it the same author on ficbook, with the same
-- numeric id"). A mark of a merge naming it as its owner names the own key.
UPDATE site_author p
SET merged_by_author = (
    SELECT MIN(k.author)
    FROM site_author n, site_author k
    WHERE n.source = p.merged_by_source AND n.author = p.merged_by_author AND n.merged_by = 'NAME'
      AND k.normalized = n.normalized AND k.source = n.source AND k.author NOT LIKE 'name:%'
)
WHERE p.merged_by_author LIKE 'name:%'
  AND EXISTS (
    SELECT 1
    FROM site_author n, site_author k
    WHERE n.source = p.merged_by_source AND n.author = p.merged_by_author AND n.merged_by = 'NAME'
      AND k.normalized = n.normalized AND k.source = n.source AND k.author NOT LIKE 'name:%'
);

DELETE FROM site_author s
WHERE s.merged_by = 'NAME' AND s.author LIKE 'name:%'
  AND EXISTS (
    SELECT 1 FROM site_author k
    WHERE k.normalized = s.normalized AND k.source = s.source AND k.author NOT LIKE 'name:%'
);
