-- The columns of the texts the sites of the tests give their works (item/ItemTexts.kt, SiteTexts.columnOf)
ALTER TABLE item ADD COLUMN IF NOT EXISTS site_text_annotation CHARACTER VARYING;
ALTER TABLE item ADD COLUMN IF NOT EXISTS site_text_pairings CHARACTER VARYING;
