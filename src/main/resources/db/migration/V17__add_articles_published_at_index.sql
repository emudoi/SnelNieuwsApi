-- Personalised feed pulls a wider pool (LIMIT 300) instead of LIMIT 100. At
-- current table sizes (~1000 rows) the sort is still cheap but the index
-- makes it bounded as the table grows.
CREATE INDEX IF NOT EXISTS idx_articles_published_at
  ON articles (published_at DESC);
