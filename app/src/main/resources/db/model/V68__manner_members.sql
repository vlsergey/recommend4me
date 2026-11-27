-- The items every normalized author's and series' manner was last made of (manner.MannerGroups): of an author the
-- items naming them, of a series the items of its works. What a group an item left is found by, when the item's
-- authors or series change — the item's old values gone with its writing
CREATE TABLE manner_member (
    kind      VARCHAR(10)  NOT NULL,
    group_key VARCHAR(200) NOT NULL,
    source    VARCHAR(100) NOT NULL,
    item_id   BIGINT       NOT NULL,
    PRIMARY KEY (kind, group_key, source, item_id)
);
CREATE INDEX manner_member_item ON manner_member (source, item_id);
