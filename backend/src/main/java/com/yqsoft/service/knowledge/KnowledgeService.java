package com.yqsoft.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yqsoft.dao.SqlStore;
import com.yqsoft.exception.BusinessException;
import com.yqsoft.persistence.MutationExecutor;
import com.yqsoft.resource.common.*;
import com.yqsoft.resource.knowledge.KnowledgeDtos;
import com.yqsoft.security.AuthContext;
import com.yqsoft.service.common.CryptoService;
import com.yqsoft.service.common.CursorService;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class KnowledgeService {
    private final SqlStore sql;
    private final MutationExecutor mutations;
    private final CryptoService crypto;
    private final CursorService cursors;
    private final ObjectMapper mapper;

    public KnowledgeService(
            SqlStore sql,
            MutationExecutor mutations,
            CryptoService crypto,
            CursorService cursors,
            ObjectMapper mapper) {
        this.sql = sql;
        this.mutations = mutations;
        this.crypto = crypto;
        this.cursors = cursors;
        this.mapper = mapper;
    }

    public PageResult<Map<String, Object>> articles(
            String query,
            String category,
            List<String> tags,
            String cursor,
            int limit,
            boolean admin,
            String status) {
        return articles(query, category, tags, cursor, limit, admin, status, null);
    }

    public PageResult<Map<String, Object>> articles(
            String query,
            String category,
            List<String> tags,
            String cursor,
            int limit,
            boolean admin,
            String status,
            String editorId) {
        checkLimit(limit);
        String scope =
                "knowledge:"
                        + admin
                        + ":"
                        + Objects.toString(query, "")
                        + ":"
                        + Objects.toString(category, "")
                        + ":"
                        + Objects.toString(tags, "")
                        + ":"
                        + status
                        + ":"
                        + editorId;
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" a.deleted_at IS NULL AND a.id > ?");
        args.add(after);
        if (!admin) where.append(" AND a.status='ACTIVE' AND v.status='PUBLISHED'");
        if (query != null && !query.isBlank()) {
            if (query.length() > 100) throw invalid("关键词过长");
            where.append(" AND (a.search_title LIKE ? OR a.search_keywords LIKE ?)");
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }
        if (category != null && !category.isBlank()) {
            allowed(category, Set.of("ROUTINE", "ACTIVITY", "MEAL_HABIT", "RECORDING", "APP_HELP"));
            where.append(" AND a.category=?");
            args.add(category);
        }
        if (admin && status != null && !status.isBlank()) {
            allowed(status, Set.of("DRAFT", "IN_REVIEW", "REJECTED", "PUBLISHED", "RETIRED"));
            where.append(
                    " AND EXISTS (SELECT 1 FROM knowledge_article_version av WHERE"
                            + " av.article_id=a.id AND av.deleted_at IS NULL AND av.status=?)");
            args.add(status);
        }
        if (admin && editorId != null) {
            where.append(
                    " AND EXISTS (SELECT 1 FROM knowledge_article_version ev WHERE"
                        + " ev.article_id=a.id AND ev.deleted_at IS NULL AND ev.author_user_id=?)");
            args.add(Ids.parse(editorId));
        }
        if (tags != null) {
            if (tags.size() > 5) throw invalid("最多筛选五个标签");
            for (String tag : tags) {
                where.append(" AND JSON_CONTAINS(a.tag_codes_json,JSON_QUOTE(?))");
                args.add(tag);
            }
        }
        args.add(limit + 1);
        List<Map<String, Object>> found =
                sql.query(
                        "SELECT a.*,v.title,v.summary,v.published_at FROM knowledge_article a LEFT"
                            + " JOIN knowledge_article_version v ON v.id=a.active_version_id WHERE"
                                + where
                                + " ORDER BY a.id LIMIT ?",
                        args.toArray());
        boolean more = found.size() > limit;
        if (more) found = new ArrayList<>(found.subList(0, limit));
        List<Map<String, Object>> items =
                found.stream().map(r -> admin ? adminArticle(r) : summary(r)).toList();
        String next =
                more
                        ? cursors.encode(
                                scope,
                                Rows.map("id", Rows.number(found.get(found.size() - 1), "id")))
                        : null;
        return PageResult.of(items, next, more);
    }

    public Map<String, Object> article(long articleId, Long versionId) {
        Map<String, Object> article = articleRow(articleId);
        Long selected = versionId == null ? nullableId(article, "active_version_id") : versionId;
        if (selected == null) throw BusinessException.notFound();
        Map<String, Object> version =
                sql.one(
                        "SELECT * FROM knowledge_article_version WHERE id=? AND article_id=? AND"
                                + " deleted_at IS NULL AND status IN ('PUBLISHED','RETIRED')",
                        selected,
                        articleId);
        Map<String, Object> view =
                summary(
                        Rows.map(
                                "id",
                                articleId,
                                "active_version_id",
                                article.get("active_version_id"),
                                "title",
                                version.get("title"),
                                "summary",
                                version.get("summary"),
                                "category",
                                article.get("category"),
                                "tag_codes_json",
                                version.get("tags_json"),
                                "published_at",
                                version.get("published_at")));
        view.putAll(
                Rows.map(
                        "versionId",
                        selected,
                        "versionNo",
                        Rows.number(version, "version_no"),
                        "bodyMarkdown",
                        version.get("body_markdown"),
                        "applicablePopulation",
                        version.get("applicable_population"),
                        "limitations",
                        version.get("limitations"),
                        "contentDate",
                        version.get("content_date"),
                        "rightsNote",
                        version.get("rights_note"),
                        "retired",
                        "RETIRED".equals(article.get("status"))
                                || "RETIRED".equals(version.get("status")),
                        "retiredReason",
                        "RETIRED".equals(article.get("status")) ? "该条目已停止当前推荐，请结合最新资料核对。" : null,
                        "sources",
                        sourcesForVersion(selected)));
        return view;
    }

    public Map<String, Object> source(long sourceId) {
        return sourceView(
                sql.one(
                        "SELECT * FROM knowledge_source WHERE id=? AND deleted_at IS NULL",
                        sourceId));
    }

    public PageResult<Map<String, Object>> sources(
            String query, String status, String cursor, int limit) {
        checkLimit(limit);
        String scope = "knowledge-sources:" + query + ":" + status;
        long after = Rows.number(cursors.decode(cursor, scope), "id");
        List<Object> args = new ArrayList<>(List.of(after));
        String where = "id>? AND deleted_at IS NULL";
        if (query != null && !query.isBlank()) {
            where += " AND (title LIKE ? OR publisher LIKE ?)";
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }
        if (status != null) {
            allowed(status, Set.of("PENDING", "VERIFIED", "UNAVAILABLE", "REJECTED"));
            where += " AND verification_status=?";
            args.add(status);
        }
        args.add(limit + 1);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT * FROM knowledge_source WHERE " + where + " ORDER BY id LIMIT ?",
                        args.toArray());
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        String next =
                more
                        ? cursors.encode(
                                scope, Rows.map("id", Rows.number(rows.get(rows.size() - 1), "id")))
                        : null;
        return PageResult.of(rows.stream().map(this::sourceView).toList(), next, more);
    }

    public MutationResult createArticle(KnowledgeDtos.ArticleCreate request) {
        return mutations.mutate(
                "KNOWLEDGE_ARTICLE_CREATE",
                request,
                false,
                () -> {
                    validateContent(request.initialVersion());
                    long id =
                            sql.insert(
                                    "knowledge_article",
                                    Rows.map(
                                            "article_code",
                                            UUID.randomUUID().toString(),
                                            "slug",
                                            request.slug(),
                                            "category",
                                            request.category(),
                                            "status",
                                            "DRAFT",
                                            "latest_version_no",
                                            1,
                                            "search_title",
                                            request.title(),
                                            "search_keywords",
                                            "",
                                            "tag_codes_json",
                                            List.of(),
                                            "sort_order",
                                            0));
                    long versionId = insertVersion(id, 1, request.initialVersion());
                    audit("KNOWLEDGE_CREATE", "KNOWLEDGE_ARTICLE", id);
                    return MutationResult.of("KNOWLEDGE_ARTICLE", id, 1)
                            .withExtra(Rows.map("draftVersionId", versionId));
                });
    }

    public MutationResult createVersion(long articleId, KnowledgeDtos.VersionCreate request) {
        return mutations.mutate(
                "KNOWLEDGE_VERSION_CREATE",
                Rows.map("articleId", articleId, "request", request),
                false,
                () -> {
                    Map<String, Object> article = lockArticle(articleId);
                    expected(article, request.expectedArticleRowVersion());
                    KnowledgeDtos.Content content = request.content();
                    if (request.copyFromVersionId() != null) {
                        if (content != null) throw invalid("复制版本与直接填写内容不能同时提交");
                        content =
                                contentFromVersion(
                                        articleId, Ids.parse(request.copyFromVersionId()));
                    }
                    if (content == null) throw invalid("请填写版本内容或选择复制来源");
                    validateContent(content);
                    int number = Rows.integer(article, "latest_version_no") + 1;
                    long id = insertVersion(articleId, number, content);
                    sql.update(
                            "knowledge_article",
                            articleId,
                            Rows.number(article, "row_version"),
                            Rows.map("latest_version_no", number));
                    audit("KNOWLEDGE_VERSION_CREATE", "KNOWLEDGE_VERSION", id);
                    return MutationResult.of("KNOWLEDGE_VERSION", id, 1);
                });
    }

    public MutationResult replaceVersion(long id, KnowledgeDtos.VersionReplace request) {
        return mutations.mutate(
                "KNOWLEDGE_VERSION_REPLACE",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    Map<String, Object> row = lockVersion(id);
                    expected(row, request.expectedRowVersion());
                    editable(row);
                    validateContent(request.content());
                    Map<String, Object> values = contentValues(request.content());
                    values.putAll(
                            Rows.map(
                                    "status",
                                    "DRAFT",
                                    "author_user_id",
                                    AuthContext.userId(),
                                    "reviewer_user_id",
                                    null,
                                    "review_comment",
                                    null,
                                    "review_decision",
                                    null));
                    long version =
                            sql.update(
                                    "knowledge_article_version",
                                    id,
                                    Rows.number(row, "row_version"),
                                    values);
                    sql.execute(
                            "DELETE FROM knowledge_version_source WHERE article_version_id=?", id);
                    insertSourceLinks(id, request.content().sourceLinks());
                    audit("KNOWLEDGE_VERSION_EDIT", "KNOWLEDGE_VERSION", id);
                    return MutationResult.of("KNOWLEDGE_VERSION", id, version);
                });
    }

    public MutationResult submit(long id, KnowledgeDtos.VersionBody request) {
        return mutations.mutate(
                "KNOWLEDGE_REVIEW_SUBMIT",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    Map<String, Object> row = lockVersion(id);
                    expected(row, request.expectedRowVersion());
                    editable(row);
                    if (sql.count(
                                    "SELECT COUNT(*) FROM knowledge_version_source WHERE"
                                            + " article_version_id=?",
                                    id)
                            == 0) {
                        throw invalid("提交审核前必须关联真实资料来源");
                    }
                    long version =
                            sql.update(
                                    "knowledge_article_version",
                                    id,
                                    Rows.number(row, "row_version"),
                                    Rows.map(
                                            "status",
                                            "IN_REVIEW",
                                            "review_decision",
                                            null,
                                            "reviewer_user_id",
                                            null,
                                            "review_comment",
                                            null));
                    audit("KNOWLEDGE_REVIEW_SUBMIT", "KNOWLEDGE_VERSION", id);
                    return MutationResult.of("KNOWLEDGE_VERSION", id, version);
                });
    }

    public MutationResult review(long id, KnowledgeDtos.Review request) {
        return mutations.mutate(
                "KNOWLEDGE_REVIEW",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    Map<String, Object> row = lockVersion(id);
                    expected(row, request.expectedRowVersion());
                    requireStatus(row, "IN_REVIEW");
                    if (Rows.number(row, "author_user_id") == AuthContext.userId()) {
                        throw BusinessException.forbidden("SELF_REVIEW_FORBIDDEN", "编辑者不能审核自己的版本");
                    }
                    boolean approved = "PASS".equals(request.decision());
                    long version =
                            sql.update(
                                    "knowledge_article_version",
                                    id,
                                    Rows.number(row, "row_version"),
                                    Rows.map(
                                            "status",
                                            approved ? "IN_REVIEW" : "REJECTED",
                                            "review_decision",
                                            approved ? "APPROVED" : "REJECTED",
                                            "reviewer_user_id",
                                            AuthContext.userId(),
                                            "review_comment",
                                            request.comment(),
                                            "source_checked_at",
                                            Instant.now()));
                    audit("KNOWLEDGE_REVIEW", "KNOWLEDGE_VERSION", id);
                    return MutationResult.of("KNOWLEDGE_VERSION", id, version);
                });
    }

    public MutationResult publish(long id, KnowledgeDtos.Publish request) {
        return mutations.mutate(
                "KNOWLEDGE_PUBLISH",
                Rows.map("id", id, "request", request),
                false,
                () -> {
                    Map<String, Object> row = lockVersion(id);
                    Map<String, Object> article = lockArticle(Rows.number(row, "article_id"));
                    expected(row, request.expectedRowVersion());
                    expected(article, request.expectedArticleRowVersion());
                    requireStatus(row, "IN_REVIEW");
                    if (!"APPROVED".equals(row.get("review_decision")))
                        throw invalid("版本须先由独立审核者通过审核");
                    if (!Objects.equals(
                            nullableId(article, "active_version_id"),
                            Ids.optional(request.expectedActiveVersionId()))) {
                        throw BusinessException.conflict("ACTIVE_VERSION_CONFLICT", "当前发布版本已变化");
                    }
                    List<Map<String, Object>> sources =
                            sql.query(
                                    "SELECT s.* FROM knowledge_version_source l JOIN"
                                        + " knowledge_source s ON s.id=l.knowledge_source_id WHERE"
                                        + " l.article_version_id=? ORDER BY s.id FOR UPDATE",
                                    id);
                    if (sources.isEmpty()
                            || sources.stream()
                                    .anyMatch(
                                            source ->
                                                    !"VERIFIED"
                                                                    .equals(
                                                                            source.get(
                                                                                    "verification_status"))
                                                            || source.get("deleted_at") != null))
                        throw invalid("存在尚未核验或已不可用来源，不能发布");
                    long version =
                            sql.update(
                                    "knowledge_article_version",
                                    id,
                                    Rows.number(row, "row_version"),
                                    Rows.map("status", "PUBLISHED", "published_at", Instant.now()));
                    sql.update(
                            "knowledge_article",
                            Rows.number(article, "id"),
                            Rows.number(article, "row_version"),
                            Rows.map(
                                    "status",
                                    "ACTIVE",
                                    "active_version_id",
                                    id,
                                    "search_title",
                                    row.get("title"),
                                    "search_keywords",
                                    row.get("summary"),
                                    "tag_codes_json",
                                    Rows.json(row, "tags_json"),
                                    "retired_at",
                                    null));
                    audit("KNOWLEDGE_PUBLISH", "KNOWLEDGE_VERSION", id);
                    return MutationResult.of("KNOWLEDGE_VERSION", id, version);
                });
    }

    public MutationResult retire(long articleId, KnowledgeDtos.Retire request) {
        return mutations.mutate(
                "KNOWLEDGE_RETIRE",
                Rows.map("id", articleId, "request", request),
                false,
                () -> {
                    Map<String, Object> row = lockArticle(articleId);
                    expected(row, request.expectedRowVersion());
                    long version =
                            sql.update(
                                    "knowledge_article",
                                    articleId,
                                    Rows.number(row, "row_version"),
                                    Rows.map("status", "RETIRED", "retired_at", Instant.now()));
                    audit("KNOWLEDGE_RETIRE", "KNOWLEDGE_ARTICLE", articleId);
                    return MutationResult.of("KNOWLEDGE_ARTICLE", articleId, version);
                });
    }

    public MutationResult saveSource(KnowledgeDtos.SourceCommand request) {
        return mutations.mutate(
                "KNOWLEDGE_SOURCE_SAVE",
                request,
                false,
                () -> {
                    String url = httpsUrl(request.url());
                    Map<String, Object> values =
                            Rows.map(
                                    "title",
                                    request.title(),
                                    "publisher",
                                    request.organization(),
                                    "canonical_url",
                                    url,
                                    "url_hash",
                                    crypto.sha256(url.getBytes(StandardCharsets.UTF_8)),
                                    "source_kind",
                                    request.sourceKind(),
                                    "published_date",
                                    request.publishedOn(),
                                    "accessed_at",
                                    request.accessedOn().atStartOfDay(ZoneOffset.UTC).toInstant(),
                                    "verification_status",
                                    request.verificationStatus(),
                                    "usage_basis",
                                    request.usageBasis(),
                                    "license_note",
                                    request.rightsNote(),
                                    "verification_note",
                                    request.verificationNote(),
                                    "source_language",
                                    "zh-CN");
                    long id;
                    long version;
                    if ("CREATE".equals(request.action())) {
                        if (request.sourceId() != null || request.expectedRowVersion() != null)
                            throw invalid("创建来源不能指定旧编号或版本");
                        values.put("source_code", UUID.randomUUID().toString());
                        id = sql.insert("knowledge_source", values);
                        version = 1;
                    } else {
                        id = Ids.parse(request.sourceId());
                        Map<String, Object> row =
                                sql.one(
                                        "SELECT * FROM knowledge_source WHERE id=? AND deleted_at"
                                                + " IS NULL FOR UPDATE",
                                        id);
                        expected(row, request.expectedRowVersion());
                        if (!url.equals(row.get("canonical_url"))
                                && sql.count(
                                                "SELECT COUNT(*) FROM knowledge_version_source l"
                                                        + " JOIN knowledge_article_version v ON"
                                                        + " v.id=l.article_version_id WHERE"
                                                        + " l.knowledge_source_id=? AND v.status IN"
                                                        + " ('IN_REVIEW','PUBLISHED','RETIRED')",
                                                id)
                                        > 0) {
                            throw BusinessException.conflict(
                                    "SOURCE_ALREADY_CITED", "已发布引用的链接不可覆盖，请登记新的资料来源");
                        }
                        version =
                                sql.update(
                                        "knowledge_source",
                                        id,
                                        Rows.number(row, "row_version"),
                                        values);
                    }
                    audit("KNOWLEDGE_SOURCE_SAVE", "KNOWLEDGE_SOURCE", id);
                    return MutationResult.of("KNOWLEDGE_SOURCE", id, version);
                });
    }

    /**
     * Only these published, verified version/source pairs may be supplied to or cited by a model.
     */
    public List<Map<String, Object>> retrieve(String text, int limit) {
        String term = text == null ? "" : text.strip();
        if (term.length() > 80) term = term.substring(0, 80);
        List<Map<String, Object>> rows =
                sql.query(
                        "SELECT v.id article_version_id,a.id"
                            + " article_id,v.title,v.summary,v.applicable_population,v.limitations,s.id"
                            + " source_id,s.title source_title,s.canonical_url,l.source_locator"
                            + " FROM knowledge_article a JOIN knowledge_article_version v ON"
                            + " v.id=a.active_version_id JOIN knowledge_version_source l ON"
                            + " l.article_version_id=v.id JOIN knowledge_source s ON"
                            + " s.id=l.knowledge_source_id WHERE a.status='ACTIVE' AND"
                            + " v.status='PUBLISHED' AND s.verification_status='VERIFIED' AND"
                            + " a.deleted_at IS NULL AND v.deleted_at IS NULL AND s.deleted_at IS"
                            + " NULL AND (?='' OR v.title LIKE ? OR v.summary LIKE ?) ORDER BY"
                            + " a.sort_order,a.id,l.reference_no LIMIT ?",
                        term,
                        "%" + term + "%",
                        "%" + term + "%",
                        Math.min(8, limit));
        return rows.stream()
                .map(
                        r ->
                                Rows.map(
                                        "articleId",
                                        Rows.number(r, "article_id"),
                                        "articleVersionId",
                                        Rows.number(r, "article_version_id"),
                                        "sourceId",
                                        Rows.number(r, "source_id"),
                                        "title",
                                        r.get("title"),
                                        "summary",
                                        r.get("summary"),
                                        "applicablePopulation",
                                        r.get("applicable_population"),
                                        "limitations",
                                        r.get("limitations"),
                                        "sourceTitle",
                                        r.get("source_title"),
                                        "sourceUrl",
                                        r.get("canonical_url"),
                                        "locator",
                                        r.get("source_locator")))
                .toList();
    }

    private long insertVersion(long articleId, int number, KnowledgeDtos.Content content) {
        Map<String, Object> values = contentValues(content);
        values.putAll(
                Rows.map(
                        "article_id",
                        articleId,
                        "version_no",
                        number,
                        "status",
                        "DRAFT",
                        "author_user_id",
                        AuthContext.userId()));
        long id = sql.insert("knowledge_article_version", values);
        insertSourceLinks(id, content.sourceLinks());
        return id;
    }

    private Map<String, Object> contentValues(KnowledgeDtos.Content content) {
        return Rows.map(
                "title",
                content.title(),
                "summary",
                content.summary(),
                "body_markdown",
                content.bodyMarkdown(),
                "applicable_population",
                content.applicablePopulation(),
                "limitations",
                content.limitations(),
                "content_digest",
                crypto.digest(content),
                "tags_json",
                content.tags() == null ? List.of() : content.tags(),
                "content_date",
                content.contentDate(),
                "rights_note",
                content.rightsNote());
    }

    private void insertSourceLinks(long versionId, List<KnowledgeDtos.SourceLink> links) {
        int index = 1;
        for (KnowledgeDtos.SourceLink link : links) {
            sql.insert(
                    "knowledge_version_source",
                    Rows.map(
                            "article_version_id",
                            versionId,
                            "knowledge_source_id",
                            Ids.parse(link.sourceId()),
                            "reference_no",
                            index++,
                            "support_scope",
                            link.citationLabel(),
                            "source_locator",
                            link.locator(),
                            "checked_at",
                            Instant.now()));
        }
    }

    private KnowledgeDtos.Content contentFromVersion(long articleId, long versionId) {
        Map<String, Object> r =
                sql.one(
                        "SELECT * FROM knowledge_article_version WHERE id=? AND article_id=? AND"
                                + " deleted_at IS NULL",
                        versionId,
                        articleId);
        List<KnowledgeDtos.SourceLink> links =
                sql
                        .query(
                                "SELECT * FROM knowledge_version_source WHERE article_version_id=?"
                                        + " ORDER BY reference_no",
                                versionId)
                        .stream()
                        .map(
                                l ->
                                        new KnowledgeDtos.SourceLink(
                                                Long.toString(
                                                        Rows.number(l, "knowledge_source_id")),
                                                Rows.text(l, "support_scope"),
                                                Rows.text(l, "source_locator"),
                                                Rows.integer(l, "reference_no")))
                        .toList();
        List<String> tags = new ArrayList<>();
        JsonNode tagJson = Rows.json(r, "tags_json");
        if (tagJson != null) tagJson.forEach(n -> tags.add(n.asText()));
        return new KnowledgeDtos.Content(
                Rows.text(r, "title"),
                Rows.text(r, "summary"),
                Rows.text(r, "body_markdown"),
                Rows.text(r, "applicable_population"),
                Rows.text(r, "limitations"),
                tags,
                links,
                Rows.date(r, "content_date"),
                Rows.text(r, "rights_note"));
    }

    private void validateContent(KnowledgeDtos.Content content) {
        if (content.sourceLinks().stream()
                        .map(KnowledgeDtos.SourceLink::sourceId)
                        .distinct()
                        .count()
                != content.sourceLinks().size()) {
            throw invalid("同一版本不能重复关联同一个来源");
        }
        if (content.tags() != null
                && content.tags().stream().distinct().count() != content.tags().size())
            throw invalid("标签不能重复");
        String body = content.bodyMarkdown().toLowerCase(Locale.ROOT);
        if (body.matches("(?s).*<[!/]?[a-z][^>]*>.*")
                || body.contains("javascript:")
                || body.contains("data:text/html")) {
            throw invalid("正文仅支持安全Markdown，请移除HTML或脚本链接");
        }
        for (KnowledgeDtos.SourceLink link : content.sourceLinks())
            source(Ids.parse(link.sourceId()));
    }

    private List<Map<String, Object>> sourcesForVersion(long id) {
        return sql
                .query(
                        "SELECT s.* FROM knowledge_source s JOIN knowledge_version_source l ON"
                                + " l.knowledge_source_id=s.id WHERE l.article_version_id=? AND"
                                + " s.deleted_at IS NULL ORDER BY l.reference_no",
                        id)
                .stream()
                .map(this::sourceView)
                .toList();
    }

    private Map<String, Object> sourceView(Map<String, Object> r) {
        return Rows.map(
                "id",
                Rows.number(r, "id"),
                "rowVersion",
                Rows.number(r, "row_version"),
                "title",
                r.get("title"),
                "organization",
                r.get("publisher"),
                "url",
                r.get("canonical_url"),
                "sourceKind",
                r.get("source_kind"),
                "publishedOn",
                r.get("published_date"),
                "accessedOn",
                Rows.instant(r, "accessed_at") == null
                        ? null
                        : Rows.instant(r, "accessed_at").atZone(ZoneOffset.UTC).toLocalDate(),
                "verificationStatus",
                r.get("verification_status"),
                "verificationNote",
                r.get("verification_note"),
                "usageBasis",
                r.get("usage_basis"),
                "rightsNote",
                r.get("license_note"));
    }

    private Map<String, Object> summary(Map<String, Object> r) {
        return Rows.map(
                "id",
                Rows.number(r, "id"),
                "activeVersionId",
                r.get("active_version_id"),
                "title",
                r.get("title"),
                "summary",
                r.get("summary"),
                "category",
                r.get("category"),
                "tags",
                Rows.json(r, "tag_codes_json"),
                "publishedAt",
                Rows.instant(r, "published_at"));
    }

    private Map<String, Object> adminArticle(Map<String, Object> r) {
        List<Map<String, Object>> versions =
                sql.query(
                        "SELECT * FROM knowledge_article_version WHERE article_id=? "
                                + "AND deleted_at IS NULL ORDER BY version_no DESC LIMIT 20",
                        Rows.number(r, "id"));
        return Rows.map(
                "id",
                Rows.number(r, "id"),
                "rowVersion",
                Rows.number(r, "row_version"),
                "slug",
                r.get("slug"),
                "title",
                r.get("search_title"),
                "activeVersionId",
                r.get("active_version_id"),
                "updatedAt",
                Rows.instant(r, "updated_at"),
                "versions",
                versions.stream()
                        .map(
                                v ->
                                        Rows.map(
                                                "id",
                                                Rows.number(v, "id"),
                                                "rowVersion",
                                                Rows.number(v, "row_version"),
                                                "versionNo",
                                                Rows.number(v, "version_no"),
                                                "status",
                                                v.get("status"),
                                                "editorId",
                                                v.get("author_user_id"),
                                                "reviewerId",
                                                v.get("reviewer_user_id"),
                                                "reviewDecision",
                                                v.get("review_decision"),
                                                "reviewComment",
                                                v.get("review_comment"),
                                                "publishedAt",
                                                Rows.instant(v, "published_at")))
                        .toList());
    }

    private Map<String, Object> articleRow(long id) {
        return sql.one("SELECT * FROM knowledge_article WHERE id=? AND deleted_at IS NULL", id);
    }

    private Map<String, Object> lockArticle(long id) {
        return sql.one(
                "SELECT * FROM knowledge_article WHERE id=? AND deleted_at IS NULL FOR UPDATE", id);
    }

    private Map<String, Object> lockVersion(long id) {
        return sql.one(
                "SELECT * FROM knowledge_article_version WHERE id=? AND deleted_at IS NULL FOR"
                        + " UPDATE",
                id);
    }

    private void editable(Map<String, Object> row) {
        if (!Set.of("DRAFT", "REJECTED").contains(Rows.text(row, "status"))) {
            throw BusinessException.conflict("VERSION_IMMUTABLE", "已提交或发布版本不能直接修改，请创建新版本");
        }
    }

    private void expected(Map<String, Object> row, String version) {
        if (Rows.number(row, "row_version") != Ids.parse(version))
            throw BusinessException.conflict("ROW_VERSION_CONFLICT", "内容已更新，请刷新");
    }

    private void requireStatus(Map<String, Object> row, String status) {
        if (!status.equals(row.get("status")))
            throw BusinessException.conflict("INVALID_STATE", "当前状态不允许此操作");
    }

    private void audit(String action, String type, long id) {
        // The shared audit table stores metadata, never draft content or user health text.
        sql.insert(
                "audit_log",
                Rows.map(
                        "actor_type",
                        "ADMIN",
                        "actor_user_id",
                        AuthContext.userId(),
                        "action_code",
                        action,
                        "resource_type",
                        type,
                        "resource_id",
                        id,
                        "outcome",
                        "SUCCESS",
                        "request_id",
                        RequestContext.requestId(),
                        "metadata_json",
                        Rows.map(),
                        "retention_until",
                        Instant.now().plus(Duration.ofDays(90))));
    }

    private String httpsUrl(String url) {
        try {
            URI uri = URI.create(url).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null) throw invalid("资料链接必须是HTTPS地址");
            return uri.toASCIIString();
        } catch (IllegalArgumentException ex) {
            throw invalid("资料链接格式无效");
        }
    }

    private Long nullableId(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : Rows.number(row, key);
    }

    private void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw invalid("分页数量应为1至100");
    }

    private void allowed(String value, Set<String> values) {
        if (!values.contains(value)) throw invalid("不支持的筛选值");
    }

    private BusinessException invalid(String message) {
        return BusinessException.badRequest("VALIDATION_FAILED", message);
    }
}
