-- The tables of a work's data bound to it by the application's own number (item.internal_id, V37-V39), not by the
-- site's id: a site that changes the ids of its works changes item.site_id alone. Made empty here, filled by V41, put
-- in the place of the tables of before by V42 - their keys, indexes and foreign keys made there, once the rows are in.

CREATE TABLE item_version_by_number(
    item_id BIGINT NOT NULL,
    version CHARACTER VARYING(500) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE item_picture_vector_by_number(
    item_id BIGINT NOT NULL,
    block CHARACTER VARYING(32) NOT NULL,
    encoder CHARACTER VARYING(100) NOT NULL,
    vec BINARY VARYING NOT NULL
);
CREATE TABLE set_vector_by_number(
    item_id BIGINT NOT NULL,
    kind CHARACTER VARYING(20) NOT NULL,
    embedding_id BIGINT NOT NULL,
    source INTEGER NOT NULL,
    vec BINARY VARYING NOT NULL
);
CREATE TABLE review_by_number(
    item_id BIGINT NOT NULL,
    review_id CHARACTER VARYING(100) NOT NULL,
    author CHARACTER VARYING(300),
    stars DOUBLE PRECISION,
    content CHARACTER LARGE OBJECT NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    text_key BIGINT
);
CREATE TABLE picture_by_number(
    item_id BIGINT NOT NULL,
    position INTEGER NOT NULL,
    url CHARACTER VARYING(2000) NOT NULL,
    preview_file CHARACTER VARYING(300),
    full_file CHARACTER VARYING(300),
    vec BINARY VARYING,
    encoder CHARACTER VARYING(100),
    analyzed_at TIMESTAMP WITH TIME ZONE,
    error CHARACTER VARYING(500),
    failures INTEGER DEFAULT 0 NOT NULL,
    retry_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE item_facet_by_number(
    item_id BIGINT NOT NULL,
    facet CHARACTER VARYING(100) NOT NULL,
    value_key CHARACTER VARYING(500) NOT NULL,
    position INTEGER NOT NULL,
    name CHARACTER VARYING(1000)
);
CREATE TABLE item_number_by_number(
    item_id BIGINT NOT NULL,
    number_key CHARACTER VARYING(100) NOT NULL,
    content DOUBLE PRECISION NOT NULL
);
CREATE TABLE part_by_number(
    item_id BIGINT NOT NULL,
    part_id CHARACTER VARYING(100) NOT NULL,
    position INTEGER NOT NULL,
    title CHARACTER VARYING(1000),
    published_at TIMESTAMP WITH TIME ZONE,
    encoded_hash CHARACTER VARYING(64),
    encoder CHARACTER VARYING(100),
    paragraphs_hash CHARACTER VARYING(64),
    paragraphs_encoder CHARACTER VARYING(100),
    chars INTEGER,
    words INTEGER,
    content_hash CHARACTER VARYING(64),
    windows INTEGER,
    taken_off_at TIMESTAMP WITH TIME ZONE,
    pieces CHARACTER VARYING(100),
    full_text CHARACTER VARYING(100)
);
CREATE TABLE item_manner_by_number(
    item_id BIGINT NOT NULL,
    words_id BIGINT NOT NULL,
    text_hash CHARACTER VARYING(64) NOT NULL,
    vec BINARY VARYING NOT NULL
);
CREATE TABLE text_window_by_number(
    item_id BIGINT NOT NULL,
    text_key CHARACTER VARYING(100) NOT NULL,
    window_no INTEGER NOT NULL,
    vec BINARY VARYING NOT NULL
);
CREATE TABLE series_place_by_number(
    series_key CHARACTER VARYING(200) NOT NULL,
    item_id BIGINT NOT NULL,
    position INTEGER NOT NULL
);
CREATE TABLE text_vector_by_number(
    item_id BIGINT NOT NULL,
    text_key CHARACTER VARYING(100) NOT NULL,
    encoder CHARACTER VARYING(100) NOT NULL,
    text_hash CHARACTER VARYING(64) NOT NULL,
    vec BINARY VARYING NOT NULL,
    windows INTEGER,
    windows_by CHARACTER VARYING(100),
    pieces CHARACTER VARYING(100),
    full_text CHARACTER VARYING(100)
);
CREATE TABLE captured_page_by_number(
    url CHARACTER VARYING(2000) NOT NULL,
    item_id BIGINT,
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL,
    parser_version INTEGER NOT NULL,
    html BINARY LARGE OBJECT NOT NULL
);
