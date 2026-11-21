-- ADR-042 (suggested resolutions, Phase K3): a small knowledge base of promoted fixes.
-- An article is promoted once from one resolved ticket (unique source_incident_id) and keeps only technical content:
-- no reporter, e-mail or department column, and the copied text has the reporter's name, e-mail and department removed.
-- The id is 'KB-' followed by the source ticket id, so it is stable and readable without a sequence.
CREATE TABLE knowledge_articles (
    id VARCHAR(80) PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    category VARCHAR(32) NOT NULL,
    problem TEXT NOT NULL,
    root_cause TEXT NOT NULL,
    steps_taken TEXT NOT NULL,
    preventive_action TEXT NOT NULL,
    source_incident_id VARCHAR(64) NOT NULL REFERENCES incidents(id) ON DELETE CASCADE ON UPDATE CASCADE,
    created_by VARCHAR(64) NOT NULL REFERENCES users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_knowledge_articles_source UNIQUE (source_incident_id)
);

-- The suggestion window reads the newest articles of one category.
CREATE INDEX idx_knowledge_articles_category_created ON knowledge_articles(category, created_at);
