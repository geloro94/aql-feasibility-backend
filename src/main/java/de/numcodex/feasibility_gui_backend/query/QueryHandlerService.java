package de.numcodex.feasibility_gui_backend.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.numcodex.feasibility_gui_backend.query.api.Query;
import de.numcodex.feasibility_gui_backend.query.api.QueryListEntry;
import de.numcodex.feasibility_gui_backend.query.api.QueryResult;
import de.numcodex.feasibility_gui_backend.query.api.QueryResultLine;
import de.numcodex.feasibility_gui_backend.query.api.QueryTemplate;
import de.numcodex.feasibility_gui_backend.query.api.SavedQuery;
import de.numcodex.feasibility_gui_backend.query.api.StructuredQuery;
import de.numcodex.feasibility_gui_backend.query.dispatch.QueryDispatchException;
import de.numcodex.feasibility_gui_backend.query.dispatch.QueryDispatcher;
import de.numcodex.feasibility_gui_backend.query.obfuscation.QueryResultObfuscator;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryContentRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.Result;
import de.numcodex.feasibility_gui_backend.query.persistence.ResultRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplateRepository;
import de.numcodex.feasibility_gui_backend.query.persistence.SavedQueryRepository;
import de.numcodex.feasibility_gui_backend.query.templates.QueryTemplateException;
import de.numcodex.feasibility_gui_backend.query.templates.QueryTemplateHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

import static de.numcodex.feasibility_gui_backend.query.persistence.ResultType.SUCCESS;

@Service
@RequiredArgsConstructor
public class QueryHandlerService {

    @NonNull
    private final QueryDispatcher queryDispatcher;

    @NonNull
    private final QueryTemplateHandler queryTemplateHandler;

    @NonNull
    private final QueryRepository queryRepository;

    @NonNull
    private final QueryContentRepository queryContentRepository;

    @NonNull
    private final ResultRepository resultRepository;

    @NonNull
    private final QueryTemplateRepository queryTemplateRepository;

    @NonNull
    private final SavedQueryRepository savedQueryRepository;

    @NonNull
    private final QueryResultObfuscator queryResultObfuscator;

    @NonNull
    private ObjectMapper jsonUtil;

    public Long runQuery(StructuredQuery structuredQuery, String userId)
        throws QueryDispatchException {
        var queryId = queryDispatcher.enqueueNewQuery(structuredQuery, userId);
        queryDispatcher.dispatchEnqueuedQuery(queryId);
        return queryId;
    }

    @Transactional
    public QueryResult getQueryResult(Long queryId) {
        return getQueryResult(queryId, true);
    }

    @Transactional
    public QueryResult getQueryResult(Long queryId, boolean obfuscateSites) {
        var singleSiteResults = resultRepository.findByQueryAndStatus(queryId, SUCCESS);

        var resultLines = singleSiteResults.stream()
                .map(ssr -> QueryResultLine.builder()
                        .siteName(obfuscateSites ? queryResultObfuscator.tokenizeSiteName(ssr) : ssr.getSite().getSiteName())
                        .numberOfPatients(ssr.getResult())
                        .build())
                .collect(Collectors.toList());

        var totalMatchesInPopulation = singleSiteResults.stream()
                .map(Result::getResult)
                .reduce(0, Integer::sum);

        return QueryResult.builder()
                .queryId(queryId)
                .resultLines(resultLines)
                .totalNumberOfPatients(totalMatchesInPopulation)
                .build();
    }

    public Query getQuery(Long queryId) throws JsonProcessingException {
        var query = queryRepository.findById(queryId);
        var savedQuery = savedQueryRepository.findByQueryId(queryId);
        if (query.isPresent()) {
            return convertQueryToApi(query.get(), savedQuery);
        } else {
            return null;
        }
    }

    public StructuredQuery getQueryContent(Long queryId) throws JsonProcessingException {
        var queryContent = queryContentRepository.findByQueryId(queryId);
        if (queryContent.isPresent()) {
            return jsonUtil.readValue(queryContent.get().getQueryContent(), StructuredQuery.class);
        } else {
            return null;
        }
    }

    public Long storeQueryTemplate(QueryTemplate queryTemplate, String userId)
        throws QueryTemplateException {
        return queryTemplateHandler.storeTemplate(queryTemplate, userId);
    }

    public Long saveQuery(Long queryId, SavedQuery savedQueryApi) {
        de.numcodex.feasibility_gui_backend.query.persistence.SavedQuery savedQuery = convertSavedQueryApiToPersistence(savedQueryApi, queryId);
        return savedQueryRepository.save(savedQuery).getId();
    }

    public de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplate getQueryTemplate(
        Long queryId, String authorId) throws QueryTemplateException {
        de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplate queryTemplate = queryTemplateRepository.findById(
            queryId).orElseThrow(QueryTemplateException::new);
        if (!queryTemplate.getQuery().getCreatedBy().equalsIgnoreCase(authorId)) {
            throw new QueryTemplateException();
        }
        return queryTemplate;
    }

    public List<de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplate> getQueryTemplatesForAuthor(
        String authorId) {
        return queryTemplateRepository.findByAuthor(authorId);
    }

    public QueryTemplate convertTemplatePersistenceToApi(
        de.numcodex.feasibility_gui_backend.query.persistence.QueryTemplate in)
        throws JsonProcessingException {
        return queryTemplateHandler.convertPersistenceToApi(in);
    }

    private Query convertQueryToApi(de.numcodex.feasibility_gui_backend.query.persistence.Query in,
        Optional<de.numcodex.feasibility_gui_backend.query.persistence.SavedQuery> savedQuery)
        throws JsonProcessingException {
        Query out = new Query();
        out.setId(in.getId());
        out.setContent(
            jsonUtil.readValue(in.getQueryContent().getQueryContent(), StructuredQuery.class));
        if (savedQuery.isPresent()) {
            out.setLabel(savedQuery.get().getLabel());
            out.setComment(savedQuery.get().getComment());
        }
        out.setResults(getQueryResult(in.getId()));
        return out;
    }

    private de.numcodex.feasibility_gui_backend.query.persistence.SavedQuery convertSavedQueryApiToPersistence(
        SavedQuery in, Long queryId) {
        var out = new de.numcodex.feasibility_gui_backend.query.persistence.SavedQuery();
        out.setQuery(queryRepository.getReferenceById(queryId));
        out.setComment(in.getComment());
        out.setLabel(in.getLabel());
        return out;
    }

    public List<de.numcodex.feasibility_gui_backend.query.persistence.Query> getQueryListForAuthor(
        String userId, boolean savedOnly) {
        Optional<List<de.numcodex.feasibility_gui_backend.query.persistence.Query>> queries;

        if (savedOnly) {
            queries = queryRepository.findSavedQueriesByAuthor(userId);
        } else {
            queries = queryRepository.findByAuthor(userId);
        }

        return queries.orElseGet(ArrayList::new);
    }

    public String getAuthorId(Long queryId) {
        return queryRepository.getAuthor(queryId).orElse(null);
    }

    public List<QueryListEntry> convertQueriesToQueryListEntries(List<de.numcodex.feasibility_gui_backend.query.persistence.Query> queryList) {
        var ret = new ArrayList<QueryListEntry>();

        queryList.forEach(q -> {
            if (q.getSavedQuery() != null) {
                ret.add(
                    new QueryListEntry(q.getId(), q.getSavedQuery().getLabel(), q.getCreatedAt()));
            } else {
                ret.add(
                    new QueryListEntry(q.getId(), null, q.getCreatedAt()));
            }
        });

        return ret;
    }

    public Long getAmountOfQueriesByUserAndInterval(String userId, int minutes) {
        return queryRepository.countQueriesByAuthorInTheLastNMinutes(userId, minutes);
    }

    public Long getRetryAfterTime(String userId, int offset, long interval) {
        return 60 * interval - queryRepository.getAgeOfNToLastQueryInSeconds(userId, offset) + 1;
    }
}
