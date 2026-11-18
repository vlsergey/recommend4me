-- The betas and the gammas of the works are people of the site, bound to normalized authors as the
-- works change (FacetDef.people): those the parser's version 15 wrote before they were bound are
-- taken away, for the kept pages read again (version 16) to write them anew — a change, bound then
DELETE FROM item_facet WHERE facet IN ('beta', 'gamma');
