-- The tables bound to the works by their numbers (V40, V41) in the place of those of before; their keys, indexes and
-- foreign keys on item(internal_id) made now that the rows are in.

DROP TABLE item_version;
ALTER TABLE item_version_by_number RENAME TO item_version;
ALTER TABLE item_version ADD CONSTRAINT item_version_key PRIMARY KEY(item_id, version);
ALTER TABLE item_version ADD CONSTRAINT item_version_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE item_picture_vector;
ALTER TABLE item_picture_vector_by_number RENAME TO item_picture_vector;
ALTER TABLE item_picture_vector ADD CONSTRAINT item_picture_vector_key PRIMARY KEY(item_id, block);
ALTER TABLE item_picture_vector ADD CONSTRAINT item_picture_vector_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE set_vector;
ALTER TABLE set_vector_by_number RENAME TO set_vector;
ALTER TABLE set_vector ADD CONSTRAINT set_vector_key PRIMARY KEY(item_id, kind);
ALTER TABLE set_vector ADD CONSTRAINT set_vector_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE review;
ALTER TABLE review_by_number RENAME TO review;
ALTER TABLE review ADD CONSTRAINT review_key PRIMARY KEY(item_id, review_id);
CREATE INDEX review_by_text_key ON review(text_key);
ALTER TABLE review ADD CONSTRAINT review_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE picture;
ALTER TABLE picture_by_number RENAME TO picture;
ALTER TABLE picture ADD CONSTRAINT picture_key PRIMARY KEY(item_id, position);
CREATE INDEX picture_by_encoder ON picture(encoder);
ALTER TABLE picture ADD CONSTRAINT picture_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE item_facet;
ALTER TABLE item_facet_by_number RENAME TO item_facet;
ALTER TABLE item_facet ADD CONSTRAINT item_facet_key PRIMARY KEY(item_id, facet, value_key);
CREATE INDEX item_facet_value ON item_facet(facet, value_key);
ALTER TABLE item_facet ADD CONSTRAINT item_facet_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE item_number;
ALTER TABLE item_number_by_number RENAME TO item_number;
ALTER TABLE item_number ADD CONSTRAINT item_number_key PRIMARY KEY(item_id, number_key);
ALTER TABLE item_number ADD CONSTRAINT item_number_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE part;
ALTER TABLE part_by_number RENAME TO part;
ALTER TABLE part ADD CONSTRAINT part_key PRIMARY KEY(item_id, part_id);
ALTER TABLE part ADD CONSTRAINT part_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE item_manner;
ALTER TABLE item_manner_by_number RENAME TO item_manner;
ALTER TABLE item_manner ADD CONSTRAINT item_manner_key PRIMARY KEY(item_id);
ALTER TABLE item_manner ADD CONSTRAINT item_manner_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE text_window;
ALTER TABLE text_window_by_number RENAME TO text_window;
ALTER TABLE text_window ADD CONSTRAINT text_window_key PRIMARY KEY(item_id, text_key, window_no);
ALTER TABLE text_window ADD CONSTRAINT text_window_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE series_place;
ALTER TABLE series_place_by_number RENAME TO series_place;
ALTER TABLE series_place ADD CONSTRAINT series_place_key PRIMARY KEY(series_key, item_id);
CREATE INDEX series_place_item ON series_place(item_id);
ALTER TABLE series_place ADD CONSTRAINT series_place_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE text_vector;
ALTER TABLE text_vector_by_number RENAME TO text_vector;
ALTER TABLE text_vector ADD CONSTRAINT text_vector_key PRIMARY KEY(item_id, text_key);
ALTER TABLE text_vector ADD CONSTRAINT text_vector_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;

DROP TABLE captured_page;
ALTER TABLE captured_page_by_number RENAME TO captured_page;
ALTER TABLE captured_page ADD CONSTRAINT captured_page_key PRIMARY KEY(url);
CREATE INDEX captured_page_by_parser ON captured_page(parser_version);

-- What the move (V41) kept of how far it got, and the mark of the tool that named the works by their numbers elsewhere
DROP TABLE IF EXISTS item_data_moved;
DROP TABLE IF EXISTS works_numbered_elsewhere;

-- The changes of the works taken in the order of their numbers, not of the sites' ids (ItemChanges)
DROP INDEX item_changed_at;
CREATE INDEX item_changed_at ON item(changed_at, internal_id);
