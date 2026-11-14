-- The categories of the archives' trees and their pages: a category is "index.php?cat=<cat>" of its archive
CREATE TABLE aff_category (
    archive VARCHAR(50) NOT NULL,
    name VARCHAR(500) NOT NULL,
    cat INT NOT NULL,
    PRIMARY KEY (archive, name, cat)
);
