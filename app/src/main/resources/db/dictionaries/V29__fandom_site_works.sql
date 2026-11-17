-- The works of a site's fandom — of how many of its site's items it is a value — counted in the queue of the
-- computations and kept (FandomSiteWorks): the screens of the universes read it, no request counting the values of
-- every item of every site (user, 2026-11-17: a universe's screen took 5 s); null till counted
ALTER TABLE fandom_site ADD COLUMN works INTEGER;
