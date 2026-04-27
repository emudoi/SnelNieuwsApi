-- Dedup articles by title (matches the upstream `ingestion_articles` constraint
-- in emudoi-snelnieuws-ingestion-api / V6__unique_title_ingestion_articles.sql).

-- Remove existing duplicate titles, keeping the earliest row per title.
DELETE FROM articles
WHERE id IN (
    SELECT id
    FROM (
        SELECT id, ROW_NUMBER() OVER (PARTITION BY title ORDER BY id) AS rn
        FROM articles
    ) t
    WHERE rn > 1
);

ALTER TABLE articles
    ADD CONSTRAINT articles_title_unique UNIQUE (title);
