-- The words of the manners counted beside as the models are ("manner", model.ModelAutoChanges): the items whose
-- chapters changed since they were fitted, fitted again once those come to a hundredth of the items with a text they
-- were fitted on (manner.Manners). The words kept learnt of their texts, nothing counted yet
INSERT INTO model_auto_changes (model, learnt, changes) SELECT 'manner', texts, 0 FROM manner_words;
