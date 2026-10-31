-- A value drafted from several words of a site is kept for each of them: the form shows word by word
-- what reached a value of the work, and "Harry/Hermione" and "Harry/Ron" both reach Harry
ALTER TABLE layer_draft DROP PRIMARY KEY;
ALTER TABLE layer_draft ADD PRIMARY KEY (source, item_id, facet, value_key, site_value);
