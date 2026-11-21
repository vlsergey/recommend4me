-- The sites of a tag are those whose tags are bound to it (site_tag), read with them: a copy kept with the counts went
-- stale from a binding till the counts were made again, and a tag whose site's tags had moved to another was still
-- filtered as of their site (owner, 2026-11-21)
ALTER TABLE tag_stat DROP COLUMN sources;
