-- How many windows of a part's text have vectors (PartStore): what is counted of the windows without reading them. The
-- vectors themselves go out of the database with the texts (V19, V20)
ALTER TABLE part ADD COLUMN windows INT;
