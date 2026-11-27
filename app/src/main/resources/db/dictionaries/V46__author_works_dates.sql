-- When the list of the works of a normalized author last changed (author.AuthorRepository): a work naming them or not
-- any more, an author bound, merged or taken apart, a work of theirs removed — what their manner is made anew by
-- (manner.GroupMannerChanges), taken by its date and its number. Null: not changed since the manners were made by the
-- reading of every work. The series alike, by theirs (normalized_series.works_at)
ALTER TABLE normalized_author ADD COLUMN works_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX normalized_author_works ON normalized_author (works_at, id);
CREATE INDEX normalized_series_works ON normalized_series (content_type, works_at, id);
