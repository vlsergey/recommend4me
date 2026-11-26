-- A column of the item's row for every text the site gives its works, as the application reads them
-- (item/ItemTexts.kt, SiteTexts.columnOf): made by the application's own migration of the texts already
-- kept (V38), made here of a new file and of a text no work had yet.
ALTER TABLE item ADD COLUMN IF NOT EXISTS site_text_annotation CHARACTER VARYING;
ALTER TABLE item ADD COLUMN IF NOT EXISTS site_text_pairings_line CHARACTER VARYING;
